// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

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
fun stripTrailingEmphasis(text: String): String {
    val withoutRun = TRAILING_EMPHASIS.replace(text, "")
    return withoutRun.trim().trimEnd('.', ',', '!', '?', ';', ':').trim()
}

/**
 * Strips leading and trailing punctuation from a candidate contact-name
 * token so contacts lookup matches the on-device "John" rather than
 * tripping over Whisper's "john,".
 */
fun cleanNameToken(token: String): String =
    token.trim().trim('.', ',', '!', '?', ';', ':', '"', '\'').trim()

/**
 * Same as [cleanNameToken] plus a leading-article strip ("a meeting" →
 * "meeting"). Used for free-form title/body tokens where the article is
 * always noise; contact-name extraction has its own filler-word handling
 * so it doesn't go through this.
 */
fun cleanTitleToken(token: String): String =
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
fun stripVerbBody(
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
val MULTI_WHITESPACE = Regex("\\s+")

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
val WORD_NUMBERS: Map<String, Int> = buildMap {
    put("a", 1); put("an", 1)
    val ones = listOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    // Ordinal ones, for compound spoken dates ("twenty ninth"). Single ordinals
    // ("ninth", "thirtieth") are left for prettytime to handle natively; only
    // the COMPOUND needs us — otherwise the tens word gets split off (see below).
    val ordinalOnes = listOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9,
    )
    val teens = listOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    val tens = listOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    ones.forEach { (w, n) -> put(w, n) }
    teens.forEach { (w, n) -> put(w, n) }
    tens.forEach { (w, n) -> put(w, n) }
    // Compound 21–99, BOTH cardinal ("twenty nine") and ordinal ("twenty
    // ninth"), in space- and hyphen-joined shapes so Whisper's varying
    // punctuation all resolves. The ordinal compounds matter for spoken dates:
    // without them, normalising "twenty ninth" would match the bare "twenty"
    // (→20) and leave a stray "ninth" that makes prettytime read "july 20" and
    // drop the time. As a single compound key it normalises to "29" cleanly.
    // Order doesn't matter — this is a map, not a regex.
    for ((tWord, tVal) in tens) {
        for ((oWord, oVal) in ones + ordinalOnes) {
            put("$tWord $oWord", tVal + oVal)
            put("$tWord-$oWord", tVal + oVal)
        }
    }
}

/**
 * True when [text] is nothing but a clock-time expression — a leftover like
 * "8pm" / "8 p.m." / "eight o'clock" / "noon" after the reminder prefix and
 * task are stripped. Used by [ReminderSlots] so a time-only reminder ("set a
 * reminder for 8 p.m.") isn't given the title "8pm" (the handler then applies
 * its own default instead).
 *
 * Every whitespace-delimited token must be time-ish: a clock number ("8",
 * "8:30", "8.30", "8pm"), a number word (eight, thirty, …), a meridiem /
 * day-part word, "o'clock", or a connector ("at" / "for"). An empty/blank
 * string counts as time-only — nothing meaningful is left.
 */
fun isOnlyTimeExpression(text: String): Boolean {
    // Dictation tacks sentence punctuation onto the last token ("at 8." / "8 p.m.")
    // — strip leading/trailing .,!?; per token so "8." still reads as the clock
    // number 8 (interior ':' in "8:30" is kept).
    val tokens = text.trim().lowercase().split(WHITESPACE)
        .map { it.trim('.', ',', '!', '?', ';') }
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    return tokens.all {
        it in TIME_ONLY_WORDS || it in DAY_TOKENS || it in WORD_NUMBERS || CLOCK_NUMBER.matches(it)
    }
}

private val WHITESPACE = Regex("""\s+""")
// A whole token that is a clock number: "8", "8:30", "8.30", "8pm", "8:30pm".
private val CLOCK_NUMBER = Regex("""(?i)\d{1,2}([:.]\d{2})?(a\.?m\.?|p\.?m\.?)?""")
private val TIME_ONLY_WORDS = setOf(
    "at", "for", "in", "on", "around", "by", "to", "from", "now", "past", "quarter", "half",
    "next", "this", "every", "later",
    "am", "a.m.", "a.m", "pm", "p.m.", "p.m",
    "o'clock", "oclock",
    "noon", "midnight", "morning", "afternoon", "evening", "tonight", "night",
    // duration units, so "remind me for eight minutes" / "in 8 minutes" (a
    // relative reminder with no task) also blanks to the default title.
    "minute", "minutes", "min", "mins", "hour", "hours", "hr", "hrs",
    "second", "seconds", "sec", "secs", "day", "days",
    "week", "weeks", "month", "months", "year", "years",
)

/**
 * Single-word tokens that name a calendar day — weekdays, months, and the common
 * relative-day words. **The one shared date vocabulary**, so the day-token list
 * can't drift between the extractors that need it: [isOnlyTimeExpression] (blank
 * a day-only reminder title), ReminderSlots' leading-time strip, CreateEventSlots'
 * trailing-time strip, and CalendarSlots' date-hint gate. Embed via [DAY_TOKEN_ALT].
 */
val DAY_TOKENS: Set<String> = buildSet {
    addAll(listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"))
    addAll(listOf("january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"))
    addAll(listOf("today", "tomorrow", "tonight"))
}

/** [DAY_TOKENS] as a regex alternation (`monday|tuesday|…`) for embedding in a
 *  larger pattern. Already lowercase; callers use `(?i)`. */
val DAY_TOKEN_ALT: String = DAY_TOKENS.joinToString("|")

/** Word-form alternation matching the keys of [WORD_NUMBERS]. Used by
 *  duration parsers as the number-token half of a "<n> <unit>" regex.
 *  Compound forms (e.g. "twenty-one", "twenty one") sit before bare tens
 *  so the longest match wins inside `\b…\b`. */
val WORD_NUMBER_ALT: String = run {
    val ones = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    val teens = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
    )
    val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    val compounds = buildList {
        for (t in tens) for (o in ones) {
            add("$t-$o")
            add("$t $o")
        }
    }
    (compounds + tens + teens + ones + listOf("a", "an")).joinToString("|")
}

/** Unit-token half of a duration regex. Matches seconds / minutes / hours
 *  in their long, short, and bare-letter forms. */
const val DURATION_UNIT_ALT =
    "seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h"

private val DURATION_DIGIT_UNIT = Regex("(\\d+)\\s*($DURATION_UNIT_ALT)?\\b")
private val DURATION_WORD_UNIT = Regex("\\b($WORD_NUMBER_ALT)\\s*($DURATION_UNIT_ALT)?\\b")

/** Empty / absent unit defaults to MINUTES — matches SetTimerSlots' lossy
 *  but practical convention ("timer for 10" means 10 minutes). */
private fun durationUnitSeconds(unit: String): Int = when {
    unit.startsWith("sec") || unit == "s" -> 1
    unit.startsWith("hour") || unit.startsWith("hr") || unit == "h" -> 3600
    else -> 60
}

/**
 * Sums every `<n> <unit>` pair in [text] and returns the total in seconds,
 * or null when no recognisable duration is present. Handles both digit
 * forms ("1 hour 30 minutes") and word forms ("an hour and four minutes").
 *
 * Used by [SetTimerSlots] for "timer for …" and by [SetAlarmSlots] to
 * accept "set an alarm for one hour and four minutes from now" — both
 * paths share this single source of truth so they can't drift again
 * (the same rule the v1.6.2 voice-fixes batch enforced for word-form
 * numbers via [WORD_NUMBERS]).
 *
 * Word-form path REQUIRES an explicit unit. Without it, "a" / "an" /
 * "one" would match the article in "set **a** timer" and add a phantom
 * minute. So only "ten minutes" / "an hour" counts, never bare "a" / "ten".
 */
fun parseDurationSeconds(text: String): Int? {
    val lower = text.lowercase()
    var total = 0
    var matchedAny = false

    DURATION_DIGIT_UNIT.findAll(lower).forEach { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@forEach
        total += n * durationUnitSeconds(m.groupValues[2])
        matchedAny = true
    }
    if (matchedAny) return total

    DURATION_WORD_UNIT.findAll(lower).forEach { m ->
        val unit = m.groupValues[2]
        if (unit.isEmpty()) return@forEach
        val n = WORD_NUMBERS[m.groupValues[1].trim()] ?: return@forEach
        total += n * durationUnitSeconds(unit)
        matchedAny = true
    }
    return if (matchedAny) total else null
}

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
 *
 * The clause is stripped only when a TIME signal (digit / real number word /
 * time unit / clock or day token) follows the lead-in IMMEDIATELY — modulo a
 * few articles/hedges ("the", "a", "next", "a couple"). This both (a) keeps an
 * ordinary tail like "in a Whole Foods order" intact (codeberg #14 — no signal
 * after "in"), and (b) when a real time DOES follow later, peels only that
 * clause: "put in an order at 3pm" → "put in an order" (the "at 3pm" goes, the
 * "in an order" stays). Bare "a"/"an" are deliberately not signals — otherwise
 * "in **a** Whole Foods order" would look number-ish (`WORD_NUMBERS` maps "a"→1).
 *
 * [allowBareDayToken] also strips a trailing day/date word with no preposition
 * ("standup tomorrow", "review monday") — wanted for event titles, off by
 * default so reminder/reschedule targets aren't trimmed unexpectedly.
 *
 * This is the SINGLE trailing-time stripper — Reminder, Reschedule, and
 * CreateEvent all route through it so they can't drift (the divergence that
 * let codeberg #14 hide in CreateEvent's old private copy).
 */
fun trailingTimeClauseRegex(
    leadIns: Set<String>,
    allowBareDayToken: Boolean = false,
): Regex {
    val prepClause =
        """\b(?:${leadIns.joinToString("|")})\b\s+(?:(?:$PRE_SIGNAL_FILLERS)\s+)*(?:$TIME_SIGNAL_ALT)"""
    val bareDay = if (allowBareDayToken) """|\b(?:$DAY_TOKEN_ALT)\b""" else ""
    return Regex("""(?i)\s+(?:$prepClause$bareDay).*$""")
}

/** Time-neutral words that may sit between a lead-in and the time signal
 *  ("in **the** morning", "to **next** week", "in **a couple** hours") without
 *  making the clause non-temporal. NOT signals themselves. */
const val PRE_SIGNAL_FILLERS =
    "the|a|an|about|around|roughly|approximately|almost|next|this|coming|following|couple|few|half"

// Number words that count as a time signal — every `WORD_NUMBERS` key except
// the bare articles "a"/"an" (which appear in ordinary titles) and the
// space/hyphen compounds (their first word already matches).
private val TRAILING_NUMBER_WORDS: String =
    WORD_NUMBERS.keys
        .filter { it != "a" && it != "an" && ' ' !in it && '-' !in it }
        .joinToString("|")
private const val TRAILING_TIME_UNITS =
    "hours?|hrs?|minutes?|mins?|seconds?|secs?|days?|weeks?|weekends?|months?|years?"
private const val TRAILING_CLOCK_WORDS =
    "noon|midnight|midday|mornings?|afternoons?|evenings?|nights?|tonight|o'?clock|[ap]\\.?m\\.?"

/**
 * Regex fragment (no anchors) matching a single TIME signal: a digit, a real
 * number word, a time unit, a clock / part-of-day word, or a day / month
 * token. Used to gate trailing-clause stripping so a title's ordinary "in/at/on
 * …" tail isn't mistaken for a spoken time (codeberg #14). Embed inside a
 * `(?: … )`; callers add `(?i)`.
 */
val TIME_SIGNAL_ALT: String =
    """\d|\b(?:$TRAILING_NUMBER_WORDS|$TRAILING_TIME_UNITS|$TRAILING_CLOCK_WORDS|$DAY_TOKEN_ALT)\b"""

/**
 * Pulls a place name out of a query of the form `… <in|at> <place>`. Used
 * by both `WeatherSlots` and `WorldTimeSlots` — same regex, same trailing-
 * filler cleanup, same temporal-phrase exclusion list. Returns null when
 * there's no preposition (bare query → handler asks "which city?") or
 * when the thing after the preposition is a temporal phrase ("in the
 * morning") rather than a location.
 */
fun extractInOrAtLocation(query: String): String? {
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