package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.messaging.MessagingTargets
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SendMessage]:
 *   - `app`     — display name of the target messaging app (`"WhatsApp"`,
 *                 `"Telegram"`, `"Signal"`). Always set when the extractor
 *                 returns non-empty.
 *   - `contact` — recipient name as the user said it.
 *   - `body`    — message text.
 *
 * Two phrasing shapes recognised:
 *
 *   1. **App-first** — `"<app> <contact> <body>"`.
 *      Examples: *"WhatsApp Mom on my way"*, *"Telegram Dad I'll be late"*,
 *      *"Signal Alex meeting moved to 5"*.
 *
 *   2. **Verb + "on" + app** — `"(text|message|send a … to) <contact> on <app> <body>"`.
 *      Examples: *"text Mom on WhatsApp on my way"*,
 *      *"send a message to Alex on Signal meeting moved"*.
 *
 * Both shapes share the same greedy contact-lookup logic from [SmsSlots]
 * (try the longest contiguous prefix as a contact name, fall back through
 * shorter prefixes until [findContact] resolves). Multi-word contacts work
 * the same way they do for SMS — *"WhatsApp John Smith on my way"* picks
 * `John Smith` as the contact when that row exists.
 *
 * Returns an empty map (signalling "no extractable slots") if the query
 * doesn't start with a recognised app name and doesn't contain `on <app>`.
 * The handler can then surface a graceful "didn't catch the app" message.
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

        // Shape 1: app name at the front.
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

        // Shape 3: "<verb> <contact> on <app> <body>" — verb at the front,
        // app named mid-sentence. Strip the verb first so the "on" we look
        // for is in the body region, not inside "text" / "send".
        val withoutVerb = stripLeadingMessagingVerb(normalised)
        val onAppSplit = matchOnApp(withoutVerb)
        if (onAppSplit != null) {
            val (target, beforeOn, afterOn) = onAppSplit
            val contact = beforeOn.trim()
            val body = afterOn.trim()
            if (contact.isNotEmpty() && body.isNotEmpty()) {
                return mapOf(
                    "app" to target.displayName,
                    "contact" to contact,
                    "body" to body,
                )
            }
        }

        return emptyMap()
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
     */
    private fun matchVerbPrefixNamingApp(text: String): Pair<com.lazydevs.wristotle.messaging.MessagingTarget, String>? {
        for (target in com.lazydevs.wristotle.messaging.MessagingTargets.ALL) {
            for (alias in target.spokenAliases.sortedByDescending { it.length }) {
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
     * Greedy contact lookup over [rest] (the body region after the app name
     * has been stripped). Mirrors [SmsSlots]' strategy: try the longest
     * contiguous N-word prefix as a contact name, walk down to 1 word.
     */
    private suspend fun extractContactAndBody(rest: String, appDisplay: String): Map<String, Any> {
        // Strip a "to "/"saying "/"that "/etc connector if present so "send
        // a WhatsApp message to Mom on my way" works after the verb-strip
        // already removed "send a whatsapp message".
        val cleaned = rest.replaceFirst(LEADING_CONNECTOR, "").trim()
        if (cleaned.isEmpty()) return emptyMap()

        val words = cleaned.split(Regex("\\s+")).map(::cleanNameToken).filter { it.isNotEmpty() }
        for (n in minOf(MAX_NAME_WORDS, words.size) downTo 1) {
            val candidate = words.subList(0, n).joinToString(" ")
            if (findContact(candidate) != null) {
                val body = if (n < words.size)
                    words.subList(n, words.size).joinToString(" ").trim()
                else ""
                if (body.isNotEmpty()) {
                    return mapOf(
                        "app" to appDisplay,
                        "contact" to candidate,
                        "body" to body,
                    )
                }
            }
        }

        // Single-space fallback so the handler at least has *something* to
        // try (matches SmsSlots' final tier). No contact-lookup success but
        // we surface the slots anyway; the handler will return "contact
        // not found" rather than the misleading "didn't catch that".
        val spaceIdx = cleaned.indexOf(' ')
        if (spaceIdx < 0) return mapOf("app" to appDisplay, "contact" to cleaned)
        val contact = cleaned.substring(0, spaceIdx).trim()
        val body = cleaned.substring(spaceIdx + 1).trim()
        return if (body.isEmpty())
            mapOf("app" to appDisplay, "contact" to contact)
        else
            mapOf("app" to appDisplay, "contact" to contact, "body" to body)
    }

    /**
     * If [text] starts with one of the known app aliases (longest match
     * first so "whats app" wins over "whats"), return the matched target
     * and the remainder of the text after the alias + a space.
     */
    private fun matchLeadingApp(text: String): Pair<com.lazydevs.wristotle.messaging.MessagingTarget, String>? {
        for (target in MessagingTargets.ALL) {
            // Sort by length descending so the longer alias matches first
            // when one is a prefix of another.
            for (alias in target.spokenAliases.sortedByDescending { it.length }) {
                if (text.startsWith("$alias ")) {
                    return target to text.substring(alias.length + 1)
                }
            }
        }
        return null
    }

    /**
     * Look for `" on <appname> "` anywhere in [text]. Returns the target +
     * the substrings before and after the `on <app>` segment, or null if
     * no match.
     */
    private fun matchOnApp(text: String): Triple<com.lazydevs.wristotle.messaging.MessagingTarget, String, String>? {
        for (target in MessagingTargets.ALL) {
            for (alias in target.spokenAliases.sortedByDescending { it.length }) {
                val needle = " on $alias "
                val idx = text.indexOf(needle)
                if (idx >= 0) {
                    val before = text.substring(0, idx)
                    val after = text.substring(idx + needle.length)
                    return Triple(target, before, after)
                }
                // Trailing "on <app>" at the very end isn't useful — no body to send.
            }
        }
        return null
    }

    /**
     * Strip the same verb prefixes [SmsSlots] strips. Keeps the slot
     * extractor consistent with SMS phrasings — *"text Mom on WhatsApp X"*
     * routes here when the NLU classifier picks SendMessage over Sms
     * (because of the "on WhatsApp" hint) and we still want the verb
     * stripped before the "on" split.
     */
    private fun stripLeadingMessagingVerb(text: String): String {
        val verb = VERB_PREFIXES.firstOrNull { text.startsWith(it) }
        return if (verb != null) text.substring(verb.length).trim() else text
    }

    private companion object {
        // Same comma-after-verb fix SmsSlots uses.
        val LEADING_COMMA_AFTER_VERB = Regex("^([a-z]+)\\s*,\\s*")

        // Verb prefixes mirroring SmsSlots.PREFIXES (so "text Mom on WhatsApp …"
        // can be handled here when the classifier routes it to SendMessage).
        // Longest first so multi-word matches win.
        val VERB_PREFIXES = listOf(
            "send a message to ", "send message to ", "send a message ", "send message ",
            "send a whatsapp message to ", "send a telegram message to ", "send a signal message to ",
            "send a whatsapp to ", "send a telegram to ", "send a signal to ",
            "send whatsapp to ", "send telegram to ", "send signal to ",
            "text ", "tell ", "message ",
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
