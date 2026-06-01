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
 * Same as [cleanNameToken] plus a leading-article strip ("a meeting" →
 * "meeting"). Used for free-form title/body tokens where the article is
 * always noise; contact-name extraction has its own filler-word handling
 * so it doesn't go through this.
 */
internal fun cleanTitleToken(token: String): String =
    cleanNameToken(token).replace(LEADING_ARTICLE, "").trim()

private val LEADING_ARTICLE = Regex("""^(?i)(the|a|an)\s+""")

/**
 * Shared body-extraction pipeline for "<verb> <target>" slot extractors
 * (CallSlots-style: strip the leading verbs and a small filler set,
 * collapse whitespace, drop trailing punctuation). Whatever remains is
 * the user's named target — contact, app, whatever the caller is
 * looking for. Empty when stripping leaves nothing.
 *
 * Trailing emphatic runs ("play spotify please please please") are
 * stripped by default — every caller feeds the result into a contact /
 * app lookup where the emphasis would always be noise. Opt out with
 * [stripEmphasis] `= false` only if you genuinely want them preserved.
 *
 * Centralised so MediaPlay / MediaTarget / OpenApp / Cancel /
 * Reschedule all normalise the same way; an edit to the trailing-
 * punctuation list (say) only has to happen once.
 */
internal fun stripVerbBody(
    query: String,
    verbs: Regex,
    fillers: Regex,
    stripEmphasis: Boolean = true,
): String {
    val trimmed = query.lowercase()
        .replace(verbs, " ")
        .replace(fillers, " ")
        .replace(MULTI_WHITESPACE, " ")
        // Single trim pass over both whitespace and punctuation —
        // Whisper transcripts like "Pause, Absorb." leave a leading
        // ", " or trailing " ." after verb stripping, and downstream
        // consumers shouldn't have to deal with either.
        .trim { it.isWhitespace() || it in TRIM_PUNCT }
    return if (stripEmphasis) stripTrailingEmphasis(trimmed) else trimmed
}

private val TRIM_PUNCT = setOf('.', ',', '!', '?')

/** Shared so the same compiled Regex is reused across slots that
 *  collapse whitespace runs. */
internal val MULTI_WHITESPACE = Regex("\\s+")

/**
 * Spoken-number words → integers, for counts ("next three meetings") and
 * durations ("for two hours", "forty five minutes"). Shared by the calendar,
 * reminder, media-seek, set-timer, and create-event slot extractors. A
 * superset — each caller's own regex gates which keys it actually looks up,
 * so extra entries are harmless.
 *
 * Includes `a` / `an` so duration parsers can resolve "for an hour" → 1×3600.
 * Callers that want to reject the bare article (e.g. SetTimer, to avoid
 * matching "set **a** timer" as 1) gate the word-form on an explicit unit.
 */
internal val WORD_NUMBERS: Map<String, Int> = mapOf(
    "a" to 1, "an" to 1, "one" to 1,
    "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
    "forty" to 40, "forty five" to 45, "forty-five" to 45, "fifty" to 50,
    "sixty" to 60, "ninety" to 90,
)

/** Word-form alternation matching the keys of [WORD_NUMBERS]. Used by
 *  duration parsers as the number-token half of a "<n> <unit>" regex. */
internal const val WORD_NUMBER_ALT =
    "a|an|one|two|three|four|five|six|seven|eight|nine|ten|" +
        "fifteen|twenty|thirty|forty|forty-five|forty five|fifty|sixty|ninety"

/** Unit-token half of a duration regex. Matches seconds / minutes / hours
 *  in their long, short, and bare-letter forms. */
internal const val DURATION_UNIT_ALT =
    "seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h"

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

/**
 * Pulls a place name out of a query of the form `… <in|at> <place>`. Used
 * by both `WeatherSlots` and `WorldTimeSlots` — same regex, same trailing-
 * filler cleanup, same temporal-phrase exclusion list. Returns null when
 * there's no preposition (bare query → handler asks "which city?") or
 * when the thing after the preposition is a temporal phrase ("in the
 * morning") rather than a location.
 */
internal fun extractInOrAtLocation(query: String): String? {
    val lower = query.lowercase()
    val match = LOCATION_AFTER_IN_AT.find(lower) ?: return null
    var loc = match.groupValues[1].trim()
    loc = LOCATION_TRAILING_FILLER.replace(loc, "").trim()
    loc = cleanNameToken(loc)
    return loc.takeIf { it.isNotEmpty() && it !in NON_LOCATIONS }
}

private val LOCATION_AFTER_IN_AT = Regex("(?i)\\b(?:in|at)\\s+(.+)$")
private val LOCATION_TRAILING_FILLER =
    Regex("(?i)\\b(right now|now|currently|at the moment|please|today|over there)\\b\\s*$")
private val NON_LOCATIONS = setOf(
    "the morning", "the afternoon", "the evening", "the night",
    "morning", "afternoon", "evening", "night",
    "a bit", "a moment", "a sec", "a second", "a minute",
)
