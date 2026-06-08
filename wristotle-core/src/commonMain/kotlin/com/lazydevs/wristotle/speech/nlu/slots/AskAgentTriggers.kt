// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

/**
 * Shared regex builder for the AskAgent lead-in.
 *
 * Two consumers need to stay in lockstep — if they ever drift, an
 * `"ask jarvis …"` query routes through `PrefixHints` to AskAgent but
 * then `AskAgentSlots` doesn't strip `"ask jarvis"` from the body and
 * the LLM gets *"ask jarvis what's the weather"* with the lead-in
 * baked in:
 *
 *  - [com.lazydevs.wristotle.speech.nlu.slots.AskAgentSlots] strips the
 *    lead-in before passing the body to the LLM.
 *  - [com.lazydevs.wristotle.speech.nlu.WatchHintRefiner] (via
 *    [com.lazydevs.wristotle.speech.nlu.PrefixHints]) routes a query whose
 *    opening matches to [com.lazydevs.wristotle.speech.nlu.Intent.AskAgent].
 *
 * Built once per `extras` snapshot; callers cache by the extras list
 * identity. User-supplied subjects are regex-escaped so a stray `c++`
 * or `[bracket]` can't blow up the compile.
 */
object AskAgentTriggers {

    /** Built-in subject keywords every user gets without configuring
     *  anything. Kept as a wide net since adding to it is cheap and the
     *  cost of a missing trigger is a confusing voice-failure. */
    val DEFAULT_SUBJECTS: List<String> = listOf(
        "agent", "claude", "ai", "llm", "assistant",
        "bot", "chatbot", "chatgpt", "chat gpt", "gpt",
    )

    /** Sanitise the user's raw input — split on commas + newlines, trim,
     *  lowercase, drop blanks. Returns a stable list with duplicates
     *  removed. Use this on both write (to normalise what we persist) and
     *  read (to defend against a hand-edited prefs file). */
    fun sanitise(raw: String): List<String> = raw
        .split('\n', ',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * The `STRIP_PREFIXES` regex `AskAgentSlots` runs on its input. Two
     * shapes accepted at the start of the query:
     *  1. **Verb form** (works for all subjects): `(ask|hey) [the] <sub>`
     *  2. **Wake-word form** (custom subjects only): `<custom-sub>`
     *
     * Built-in subjects are NOT bare-matchable because words like `agent`
     * / `ai` / `bot` are too generic — they could legitimately appear at
     * the start of a non-AskAgent query (*"AI is interesting"*). Custom
     * subjects are user-supplied wake words (typically proper names like
     * "jarvis" / "alfred"), so bare matching is the natural pattern.
     *
     * Trailing whitespace required so a bare `"jarvis"` (no body) leaves
     * us with an empty string — treated as the no-query hint by the slot.
     */
    fun stripRegex(extras: List<String>): Regex {
        val verbForm = "(ask|hey)\\s+(the\\s+)?(${subjectAlt(extras)})\\b"
        val wakeWordForm = wakeWordAlt(extras)?.let { "($it)\\b" }
        val combined = listOfNotNull(verbForm, wakeWordForm).joinToString("|")
        return Regex(
            """(?ix)
            ^\s*
            (
              $combined
            )
            (\s*[:,.;!?\-])?
            \s+
            """,
        )
    }

    /**
     * The `PrefixHints`-style regex used by [WatchHintRefiner] to route a
     * query to AskAgent. Same shape as [stripRegex] but stops at the
     * subject word boundary — the routing decision doesn't care about the
     * body. `containsMatchIn` semantics work either way since we anchor
     * with `^\s*`.
     */
    fun routeRegex(extras: List<String>): Regex {
        val verbForm = "(ask|hey)\\s+(the\\s+)?(${subjectAlt(extras)})\\b"
        val wakeWordForm = wakeWordAlt(extras)?.let { "($it)\\b" }
        val combined = listOfNotNull(verbForm, wakeWordForm).joinToString("|")
        return Regex("""(?i)^\s*(?:$combined)""")
    }

    /** Builds the `(agent|claude|...|<extras>)` alternation for the
     *  verb-form match (covers every subject, default + custom). Each
     *  user-supplied entry is `Regex.escape`'d so metacharacters in the
     *  entry can't break compilation. */
    private fun subjectAlt(extras: List<String>): String {
        val all = DEFAULT_SUBJECTS + extras.filter { it.isNotBlank() }.map { Regex.escape(it) }
        return all.joinToString("|")
    }

    /** Alternation for the wake-word shape — custom subjects ONLY (no
     *  defaults; `agent` / `ai` / `bot` are too generic to bare-match).
     *  Returns null when extras is empty so the regex doesn't try to
     *  compile `()` (which would match the empty string everywhere). */
    private fun wakeWordAlt(extras: List<String>): String? {
        val cleaned = extras.filter { it.isNotBlank() }.map { Regex.escape(it) }
        return cleaned.takeIf { it.isNotEmpty() }?.joinToString("|")
    }
}