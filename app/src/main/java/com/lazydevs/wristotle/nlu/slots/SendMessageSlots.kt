// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.messaging.MessagingTarget
import com.lazydevs.wristotle.messaging.MessagingTargets
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SendMessage]:
 *   - `app`     — display name of the target (`"SMS"`, `"WhatsApp"`,
 *                  `"Telegram"`, `"Signal"`). Always set when the
 *                  extractor returns non-empty.
 *   - `contact` — recipient name as the user said it.
 *   - `body`    — message text.
 *
 * Four phrasing shapes recognised:
 *
 *   1. **App-first** — `"<app> <contact> <body>"`.
 *      Examples: *"WhatsApp Mom on my way"*, *"Telegram Dad I'll be late"*,
 *      *"Signal Alex meeting moved to 5"*.
 *
 *   2. **Verb naming app** — `"send a <app> message to <contact> <body>"`,
 *      `"send a <app> to <contact> <body>"`.
 *      The app token sits *inside* the verb prefix; both must be
 *      peeled together so the generic verb-strip below doesn't consume
 *      the app name first.
 *
 *   3. **Verb + "on" + app** — `"(text|message|send a … to) <contact> on <app> <body>"`.
 *      Examples: *"text Mom on WhatsApp on my way"*,
 *      *"send a message to Alex on Signal meeting moved"*.
 *
 *   4. **Bare verb, no app named** — `"text <contact> <body>"`,
 *      *"send a message to <contact> saying <body>"*, etc. Falls back
 *      to the default SMS target (uses [SmsSlots]' greedy contact
 *      lookup + conjunction-split logic internally). This is the
 *      one shape Phase A2 added — Phase A1 returned empty here, which
 *      now defaults to SMS so SmsHandler isn't needed on the side.
 *
 * Returns an empty map only when no contact / body shape was
 * extractable; that surfaces as "didn't catch that" rather than a
 * misleading "Contact not found" prompt.
 */
class SendMessageSlots(
    private val findContact: suspend (String) -> ContactsRepository.Contact?,
) : SlotExtractor {
    constructor(contacts: ContactsRepository) : this(contacts::findContact)

    override suspend fun extract(query: String): Map<String, Any> {
        val lower = query.lowercase().trim()
        if (lower.isEmpty()) return emptyMap()

        // Comma-after-verb normalisation (same Whisper quirk SmsSlots handles).
        val normalised = lower.replaceFirst(LEADING_COMMA_AFTER_VERB, "$1 ")

        // Shape 1: app name at the front. Once an app is named
        // explicitly we commit to it — even if extraction yields only
        // a partial result (app + contact, no body), don't fall
        // through to Shape 4 (SMS). The user said "WhatsApp" on
        // purpose; the handler will surface "No message body" instead
        // of silently routing to SMS.
        val leadingApp = matchLeadingApp(normalised)
        if (leadingApp != null) {
            val (target, rest) = leadingApp
            return extractContactAndBody(rest, target.displayName)
        }

        // Shape 2: verb prefix that names the app — "send a whatsapp
        // message to <contact> <body>" / "send a signal to <contact> <body>".
        // The app is *inside* the verb here, so we have to peel both off
        // together. Must run BEFORE the generic verb-strip below or the
        // generic strip would consume the app token.
        val verbWithApp = matchVerbPrefixNamingApp(normalised)
        if (verbWithApp != null) {
            val (target, rest) = verbWithApp
            return extractContactAndBody(rest, target.displayName)
        }

        // Shape 3: "<verb> <contact> on <app> <body>" — verb at the
        // front, app named mid-sentence. Strip the verb first so the
        // "on" we look for is in the body region, not inside "text" /
        // "send".
        val withoutVerb = stripLeadingMessagingVerb(normalised)
        val onAppSplit = matchOnApp(withoutVerb)
        if (onAppSplit != null) {
            val (target, beforeOn, afterOn) = onAppSplit
            val contact = beforeOn.trim()
            val body = afterOn.trim()
            if (contact.isNotEmpty() && body.isNotEmpty()) {
                return mapOf(
                    SlotKeys.App to target.displayName,
                    SlotKeys.Contact to contact,
                    SlotKeys.Body to body,
                )
            }
        }

        // Shape 4: no explicit app named → default to SMS. Inlined from
        // the original SmsSlots class (removed in Phase A3); see
        // [extractSmsLike] for the greedy-contact-lookup +
        // conjunction-split + single-space-fallback logic. Pass the
        // already-lowered `normalised` through so Shape 4 doesn't redo
        // the lowercase + comma-strip work this method already did.
        val smsResult = extractSmsLike(normalised)
        if (smsResult.isNotEmpty()) {
            return mapOf(SlotKeys.App to MessagingTargets.Sms.displayName) + smsResult
        }

        return emptyMap()
    }

    /**
     * SMS-style slot extraction — the bare-verb shape (`"text mom hi"` /
     * `"send a message to mom saying running late"` / `"tell dad …"`).
     * Used by [extract]'s Shape 4 fallback when no messaging app is
     * named.
     *
     * Returns `contact` and (optionally) `body`. The `app` key is added
     * by the caller — this function is purposely shape-compatible with
     * the original SmsSlots so its tests carry over verbatim.
     *
     * Strategy:
     *  1. Strip a Whisper-inserted comma after the verb (*"text, mom
     *     hi"* → *"text mom hi"*).
     *  2. Strip the longest matching verb prefix (`"text "` /
     *     `"send a message to "` / *"tell "* / …).
     *  3. If a `saying` / `that` / `telling them` conjunction is
     *     present, split there — left is contact, right is body.
     *  4. Else greedy contact-name lookup: try the longest N-word
     *     prefix (down to 1) against [findContact]; longest match wins.
     *  5. Else single-space fallback so the handler at least has
     *     *something* to react to.
     */
    private suspend fun extractSmsLike(query: String): Map<String, Any> {
        // Caller (`extract`) already lowercased + comma-normalised; the
        // legacy SmsSlots-style tests pass the raw query in, so accept
        // either by re-running idempotent normalisation. Both passes are
        // cheap regex hits that do nothing when the input is already clean.
        val lower = query.lowercase().trim()
        val normalised = lower.replaceFirst(LEADING_COMMA_AFTER_VERB, "$1 ")

        val prefix = SMS_LIKE_PREFIXES.firstOrNull { normalised.startsWith(it) }
        val rest = (if (prefix != null) normalised.substring(prefix.length) else normalised).trim()
        if (rest.isEmpty()) return emptyMap()

        val conjMatch = SMS_LIKE_CONJUNCTIONS.find(rest)
        if (conjMatch != null) {
            // Strip trailing emphasis from the contact side only — the
            // body is meaningful payload, even if it's "yes yes yes".
            val contact = stripTrailingEmphasis(rest.substring(0, conjMatch.range.first).trim())
            val body = rest.substring(conjMatch.range.last + 1).trim()
            // Only accept the conjunction split when the contact half
            // actually resolves. `that` in particular is too common in
            // natural speech and otherwise greedy-matches deep into the
            // body — fall through to the multi-word loop instead.
            val resolved = if (contact.isNotEmpty() && body.isNotEmpty()) findContact(contact) else null
            if (resolved != null) {
                return mapOf(
                    SlotKeys.Contact to contact,
                    SlotKeys.Body to body,
                    SlotKeys.ResolvedContact to resolved,
                )
            }
        }

        val words = rest.split(MULTI_WHITESPACE).map(::cleanNameToken).filter { it.isNotEmpty() }
        for (n in minOf(MAX_NAME_WORDS, words.size) downTo 1) {
            val candidate = words.subList(0, n).joinToString(" ")
            val resolved = findContact(candidate)
            if (resolved != null) {
                val body = if (n < words.size) words.subList(n, words.size).joinToString(" ").trim() else ""
                if (body.isNotEmpty()) {
                    return mapOf(
                        SlotKeys.Contact to candidate,
                        SlotKeys.Body to body,
                        SlotKeys.ResolvedContact to resolved,
                    )
                }
            }
        }

        val spaceIdx = rest.indexOf(' ')
        if (spaceIdx < 0) return mapOf(SlotKeys.Contact to rest)
        val contact = rest.substring(0, spaceIdx).trim()
        val body = rest.substring(spaceIdx + 1).trim()
        return if (body.isEmpty()) mapOf(SlotKeys.Contact to contact)
               else mapOf(SlotKeys.Contact to contact, SlotKeys.Body to body)
    }

    /**
     * Match patterns where the verb prefix itself names the app:
     *   - `"send a <app> message to "`
     *   - `"send a <app> to "`
     *   - `"send <app> to "`
     *
     * Returns the target + the remainder after the verb. The remainder
     * starts at the contact name (the trailing "to " is consumed) so
     * extractContactAndBody can do its greedy lookup directly.
     *
     * Walks [MessagingTargets.NAMED] only — SMS has no aliases and is
     * never matched this way.
     */
    private fun matchVerbPrefixNamingApp(text: String): Pair<MessagingTarget, String>? {
        for (target in MessagingTargets.NAMED) {
            for (alias in target.aliasesByLengthDesc) {
                for (template in VERB_PREFIX_WITH_APP_TEMPLATES) {
                    val prefix = template.replace("<app>", alias)
                    if (text.startsWith(prefix)) {
                        return target to text.substring(prefix.length)
                    }
                }
            }
        }
        return null
    }

    /**
     * Greedy contact lookup over [rest] (the body region after the app
     * name has been stripped). Mirrors [SmsSlots]' strategy: try the
     * longest contiguous N-word prefix as a contact name, walk down to
     * 1 word; fall back to a single-space split so the handler always
     * has *something* to surface.
     *
     * Always returns at least the `app` slot — Shape 1/2 callers
     * commit to their app name, so an empty return would silently lose
     * that. Partial returns (app + contact, no body) let the handler
     * say *"No message body"* against the correct target.
     */
    private suspend fun extractContactAndBody(rest: String, appDisplay: String): Map<String, Any> {
        // Strip a "to "/"saying "/"that "/etc connector if present so
        // "send a WhatsApp message to Mom on my way" works after the
        // verb-strip already removed "send a whatsapp message".
        val cleaned = rest.replaceFirst(LEADING_CONNECTOR, "").trim()
        if (cleaned.isEmpty()) return mapOf(SlotKeys.App to appDisplay)

        val words = cleaned.split(MULTI_WHITESPACE).map(::cleanNameToken).filter { it.isNotEmpty() }

        // Greedy contact-name lookup — try the longest N-word prefix
        // first; the longest prefix that resolves wins, remainder is
        // the body.
        for (n in minOf(MAX_NAME_WORDS, words.size) downTo 1) {
            val candidate = words.subList(0, n).joinToString(" ")
            val resolved = findContact(candidate)
            if (resolved != null) {
                val body = if (n < words.size)
                    words.subList(n, words.size).joinToString(" ").trim()
                else ""
                return if (body.isNotEmpty())
                    mapOf(
                        SlotKeys.App to appDisplay,
                        SlotKeys.Contact to candidate,
                        SlotKeys.Body to body,
                        SlotKeys.ResolvedContact to resolved,
                    )
                else
                    mapOf(
                        SlotKeys.App to appDisplay,
                        SlotKeys.Contact to candidate,
                        SlotKeys.ResolvedContact to resolved,
                    )
            }
        }

        // Single-space fallback so the handler at least has *something*
        // to try (matches SmsSlots' final tier). No contact-lookup
        // success but we surface the slots anyway; the handler will
        // return "Contact not found: X" rather than the misleading
        // "didn't catch that".
        val spaceIdx = cleaned.indexOf(' ')
        if (spaceIdx < 0) return mapOf(SlotKeys.App to appDisplay, SlotKeys.Contact to cleaned)
        val contact = cleaned.substring(0, spaceIdx).trim()
        val body = cleaned.substring(spaceIdx + 1).trim()
        return if (body.isEmpty())
            mapOf(SlotKeys.App to appDisplay, SlotKeys.Contact to contact)
        else
            mapOf(SlotKeys.App to appDisplay, SlotKeys.Contact to contact, SlotKeys.Body to body)
    }

    /**
     * If [text] starts with one of the known app aliases (longest match
     * first so "whats app" wins over "whats"), return the matched
     * target and the remainder of the text after the alias + a space.
     *
     * Walks [MessagingTargets.NAMED] only.
     */
    private fun matchLeadingApp(text: String): Pair<MessagingTarget, String>? {
        for (target in MessagingTargets.NAMED) {
            // Sort by length descending so the longer alias matches
            // first when one is a prefix of another.
            for (alias in target.aliasesByLengthDesc) {
                if (text.startsWith("$alias ")) {
                    return target to text.substring(alias.length + 1)
                }
            }
        }
        return null
    }

    /**
     * Look for `" on <appname> "` anywhere in [text]. Returns the
     * target + the substrings before and after the `on <app>` segment,
     * or null if no match.
     */
    private fun matchOnApp(text: String): Triple<MessagingTarget, String, String>? {
        for (target in MessagingTargets.NAMED) {
            for (alias in target.aliasesByLengthDesc) {
                val needle = " on $alias "
                val idx = text.indexOf(needle)
                if (idx >= 0) {
                    val before = text.substring(0, idx)
                    val after = text.substring(idx + needle.length)
                    return Triple(target, before, after)
                }
            }
        }
        return null
    }

    /**
     * Strip the same verb prefixes [SmsSlots] strips. Keeps the slot
     * extractor consistent with SMS phrasings — *"text Mom on WhatsApp
     * X"* routes here when the NLU classifier picks SendMessage over
     * Sms (because of the "on WhatsApp" hint) and we still want the
     * verb stripped before the "on" split.
     */
    private fun stripLeadingMessagingVerb(text: String): String {
        val verb = VERB_PREFIXES.firstOrNull { text.startsWith(it) }
        return if (verb != null) text.substring(verb.length).trim() else text
    }

    private companion object {
        // Whisper occasionally adds a comma after the leading verb
        // ("text, mom hi") which prevents prefix-strip from firing. Same
        // normalisation the original SmsSlots used.
        val LEADING_COMMA_AFTER_VERB = Regex("^([a-z]+)\\s*,\\s*")

        // Verb prefixes shared between Shape 3 ([stripLeadingMessagingVerb])
        // and Shape 4 ([extractSmsLike]) — keeping one source of truth so a
        // future "shoot " / "ping " addition lands in both. Order matters
        // (longest first) so multi-word forms win against their own prefixes.
        private val BASE_VERB_PREFIXES = listOf(
            "send a message to ", "send message to ", "send a message ", "send message ",
            "text ", "tell ", "message ",
        )

        // SMS-style verb prefixes used by [extractSmsLike]. Adds the bare
        // "send sms" forms and the legacy "let " prefix the original
        // SmsSlots class accepted.
        val SMS_LIKE_PREFIXES = BASE_VERB_PREFIXES + listOf(
            "send sms to ", "send sms ", "let ",
        )

        // SMS-style conjunctions for the contact/body split (formerly
        // SmsSlots.CONJUNCTIONS).
        val SMS_LIKE_CONJUNCTIONS = Regex("(?i)\\b(saying|that|telling (them|him|her))\\b")

        // Verb prefixes used by [stripLeadingMessagingVerb] (Shape 3's
        // pre-strip for the "<verb> contact on <app> body" form). Adds the
        // app-naming verb variants (WhatsApp / Telegram / Signal).
        val VERB_PREFIXES = BASE_VERB_PREFIXES + listOf(
            "send a whatsapp message to ", "send a telegram message to ", "send a signal message to ",
            "send a whatsapp to ", "send a telegram to ", "send a signal to ",
            "send whatsapp to ", "send telegram to ", "send signal to ",
        )

        // Connectors that may appear right after the app name + before
        // the contact ("send a whatsapp message to Mom …" → after verb-
        // strip we have "to Mom …" — strip the "to " here).
        val LEADING_CONNECTOR = Regex("^(to|saying|that|about)\\s+")

        // Verb-prefix templates where the app is mentioned inside the
        // verb. Each template uses `<app>` as the slot to interpolate
        // each known alias into. Order is longest-first so "send a … to"
        // wins over "send …" when both could match.
        val VERB_PREFIX_WITH_APP_TEMPLATES = listOf(
            "send a <app> message to ",
            "send an <app> message to ",
            "send <app> message to ",
            "send a <app> to ",
            "send an <app> to ",
            "send <app> to ",
            "shoot <app> a message to ",
            "shoot a <app> to ",
            "shoot a <app> message to ",
        )

        const val MAX_NAME_WORDS = 4
    }
}