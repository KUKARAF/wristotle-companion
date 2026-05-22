package com.lazydevs.wristotle.nlu.slots

/**
 * Shared cleanup helpers for slot extractors.
 *
 * Whisper transcripts often append emphatic affirmation runs to the end
 * of a query ("call john yes yes yes", "text alice please please please")
 * — either because the user trailed off, the watch silence detector took
 * a moment to fire, or Whisper hallucinated them. None of these tokens
 * are part of a contact name; dragging them into a contacts lookup
 * guarantees a "not found" response.
 *
 * Trigger requires **2 or more** consecutive emphatic words. One trailing
 * "please" can be ambiguous (filler vs. polite contact suffix); a run of
 * two or more is unambiguously trailing noise.
 */
private val TRAILING_EMPHASIS = Regex(
    "(?i)(?:[\\s,.!?]+\\b(yes|no|nope|yeah|ok|okay|sure|please|maybe|right|huh|um|uh|oh|whatever)\\b){2,}[\\s,.!?]*$"
)

/**
 * Returns [text] with any trailing run of emphatic affirmations and
 * residual punctuation removed. Idempotent; safe to apply multiple
 * times. Returns the original string unchanged when nothing matches.
 */
internal fun stripTrailingEmphasis(text: String): String {
    val withoutRun = TRAILING_EMPHASIS.replace(text, "")
    return withoutRun.trim().trimEnd('.', ',', '!', '?', ';', ':').trim()
}

/**
 * Strips leading and trailing punctuation from a candidate contact-name
 * token so contacts lookup matches the on-device "John" rather than
 * tripping over Whisper's "john,".
 */
internal fun cleanNameToken(token: String): String =
    token.trim().trim('.', ',', '!', '?', ';', ':', '"', '\'').trim()

/**
 * Shared body-extraction pipeline for "<verb> <target>" slot extractors
 * (CallSlots-style: strip the leading verbs and a small filler set,
 * collapse whitespace, drop trailing punctuation). Whatever remains is
 * the user's named target — contact, app, whatever the caller is
 * looking for. Empty when stripping leaves nothing.
 *
 * Centralised so MediaPlay / MediaTarget / OpenApp all normalise the
 * same way; an edit to the trailing-punctuation list (say) only has
 * to happen once.
 */
internal fun stripVerbBody(query: String, verbs: Regex, fillers: Regex): String =
    query.lowercase()
        .replace(verbs, " ")
        .replace(fillers, " ")
        .replace(MULTI_WHITESPACE, " ")
        // Single trim pass over both whitespace and punctuation —
        // Whisper transcripts like "Pause, Absorb." leave a leading
        // ", " or trailing " ." after verb stripping, and downstream
        // consumers shouldn't have to deal with either.
        .trim { it.isWhitespace() || it in TRIM_PUNCT }

private val TRIM_PUNCT = setOf('.', ',', '!', '?')

private val MULTI_WHITESPACE = Regex("\\s+")

/**
 * Spoken-number words → integers, for counts ("next three meetings") and
 * durations ("for two hours", "forty five minutes"). Shared by the calendar
 * and reminder slot extractors. A superset — each caller's own regex gates
 * which keys it actually looks up, so extra entries are harmless.
 */
internal val WORD_NUMBERS: Map<String, Int> = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
    "forty five" to 45, "forty-five" to 45, "fifty" to 50, "sixty" to 60,
)

/**
 * Builds a regex matching a trailing " <lead-in> <rest>" time/target clause,
 * for peeling a spoken time off the end of a title/target ("call mom at 5pm"
 * → "call mom"). [leadIns] are the prepositions/markers that introduce the
 * clause — they differ by domain (at/in/on for reminders; to/until/for for
 * reschedule), so each caller supplies its own set.
 *
 * The lead-in is matched only at a word boundary with whitespace on both
 * sides, so the "at" inside "chat", the "to" inside "auto", etc. are never
 * stripped mid-word — the single place that bug-class is handled.
 */
internal fun trailingTimeClauseRegex(leadIns: Set<String>): Regex =
    Regex("""(?i)\s+\b(${leadIns.joinToString("|")})\b\s+[\w\s:.,]+$""")
