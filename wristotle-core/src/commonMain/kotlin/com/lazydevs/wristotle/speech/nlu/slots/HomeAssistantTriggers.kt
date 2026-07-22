// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

/**
 * Shared regex builder for the Home Assistant lead-in — the twin of
 * [AskAgentTriggers], same two-consumer contract:
 *
 *  - [com.lazydevs.wristotle.speech.nlu.slots.HomeAssistantSlots] strips the
 *    lead-in before POSTing the body to Home Assistant's conversation API.
 *  - [com.lazydevs.wristotle.speech.nlu.WatchHintRefiner] (via
 *    [com.lazydevs.wristotle.speech.nlu.PrefixHints]) routes a query whose
 *    opening matches to
 *    [com.lazydevs.wristotle.speech.nlu.Intent.HomeAssistant].
 *
 * If the two drift, `"hey home assistant turn off the lights"` routes to
 * HomeAssistant but the lead-in isn't stripped and HA gets the wake word
 * baked into the command.
 *
 * Two differences from AskAgent's triggers, both because HA's built-in
 * subjects ("home assistant" / "hass") are specific noun phrases, not generic
 * words like "agent" / "ai":
 *  1. **The verb is optional** — bare "home assistant turn off the lights"
 *     (no "ask"/"hey"/"tell") is the natural phrasing and routes on its own.
 *  2. The verb set adds `tell` (HA commands are imperative:
 *     "tell home assistant to lock the door"), and an optional trailing `to`
 *     connector is consumed.
 *
 * Subjects with internal whitespace are compiled to `\s+` so "home assistant"
 * matches regardless of how the recogniser spaced it. Custom entries are
 * regex-escaped token-by-token.
 */
object HomeAssistantTriggers {

    /** Built-in subject keywords every user gets for free. Specific enough to
     *  bare-match (unlike AskAgent's generic net). `"ha"` is deliberately
     *  excluded — far too generic. `"homeassistant"` is redundant with the
     *  `\s+`-collapsed "home assistant" but kept explicit for clarity. */
    val DEFAULT_SUBJECTS: List<String> = listOf(
        "home assistant", "homeassistant", "hass",
    )

    /** Sanitise the user's raw custom-trigger input — split on commas +
     *  newlines, trim, lowercase, drop blanks, de-dupe. Run on both write
     *  (normalise what we persist) and read (defend a hand-edited prefs file).
     *  Same contract as [AskAgentTriggers.sanitise]. */
    fun sanitise(raw: String): List<String> = raw
        .split('\n', ',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * The strip regex [HomeAssistantSlots] runs on its input. Removes the
     * lead-in in either shape:
     *  1. **Verb form**: `(ask|hey|tell) [the] <subject> [to]`
     *  2. **Bare form**: `<subject>`
     *
     * followed by optional punctuation and the mandatory separating
     * whitespace, so the body is the bare command. Regex backtracking handles
     * the case where the optional `to` would consume the first word of the
     * command (e.g. "… toaster on").
     */
    fun stripRegex(extras: List<String>): Regex {
        val subjects = subjectAlt(extras)
        val verbForm = "(?:ask|hey|tell)\\s+(?:the\\s+)?(?:$subjects)\\b(?:\\s+to)?"
        val bareForm = "(?:$subjects)\\b"
        return Regex("(?i)^\\s*(?:$verbForm|$bareForm)(?:\\s*[:,.;!?\\-])?\\s+")
    }

    /**
     * The routing regex used by [com.lazydevs.wristotle.speech.nlu.WatchHintRefiner].
     * Same subject shapes as [stripRegex] but stops at the subject boundary —
     * routing doesn't care about the body.
     */
    fun routeRegex(extras: List<String>): Regex {
        val subjects = subjectAlt(extras)
        val verbForm = "(?:ask|hey|tell)\\s+(?:the\\s+)?(?:$subjects)\\b"
        val bareForm = "(?:$subjects)\\b"
        return Regex("(?i)^\\s*(?:$verbForm|$bareForm)")
    }

    /** `(home\s+assistant|homeassistant|hass|<extras>)` alternation — every
     *  subject, default + custom, each token regex-escaped and internal
     *  whitespace collapsed to `\s+`. */
    private fun subjectAlt(extras: List<String>): String {
        val all = DEFAULT_SUBJECTS + extras.filter { it.isNotBlank() }
        return all.joinToString("|") { toPattern(it) }
    }

    /** Turn a plain subject phrase into a regex fragment: split on whitespace,
     *  regex-escape each token, rejoin with `\s+`. So "home assistant" →
     *  `home\s+assistant` (a literal space in the pattern would be brittle),
     *  and a metachar-bearing custom like `c++` can't break the compile. */
    private fun toPattern(subject: String): String =
        subject.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
}
