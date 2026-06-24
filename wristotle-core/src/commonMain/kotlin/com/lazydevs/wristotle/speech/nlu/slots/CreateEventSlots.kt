// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.CreateEvent]:
 *   - `time`            — [Instant] start time, parsed via [TimeParser]
 *                         (Android impl: PrettyTime + word-form numbers).
 *                         Required; absent → handler reports it couldn't
 *                         parse a time.
 *   - `title`           — explicit event title from "called/titled/about/for X".
 *                         Absent → handler defaults to "Meeting" (+ attendee).
 *   - `attendee`        — the "with X" participant, used to build a default
 *                         title ("Meeting with Alex") when no explicit title.
 *   - `durationMinutes` — Int, parsed from "for one hour" / "30 minute" /
 *                         "half hour". Absent → handler defaults to 60.
 *
 * R2 batch 4 — lifted from :app; [TimeParser] injected via constructor.
 */
class CreateEventSlots(
    private val timeParser: TimeParser,
    private val clock: Clock = Clock.System,
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()

        // Shared parse-and-roll so a past clock time isn't created in the past.
        timeParser.resolveClockTime(query, clock)?.let { out[SlotKeys.Time] = it }

        // Title/attendee are read off the raw query — both regexes are
        // keyword-anchored (called/titled/about, with), so a time phrase
        // before the title ("…at 2pm called standup") is preserved. A time
        // phrase AFTER the title ("…called standup at 3pm") is greedily
        // captured by `(.+)$`, so strip a trailing time clause back off.
        TITLE.find(query)?.groupValues?.getOrNull(2)
            ?.replace(TRAILING_TIME, "")
            ?.let { cleanTitleToken(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { out[SlotKeys.Title] = it.replaceFirstChar(Char::uppercaseChar) }

        // Bare title (no "called"/"titled"/"about"): take the words between the
        // create-event lead-in and the date/time / "with <attendee>" clause —
        // "create an event dentist appointment july 29 at 3pm" → "Dentist
        // appointment". A date-only remainder ("june twenty ninth") is rejected
        // so a bare date never becomes a title.
        if (SlotKeys.Title !in out) {
            bareTitle(query)?.let { out[SlotKeys.Title] = it.replaceFirstChar(Char::uppercaseChar) }
        }

        ATTENDEE.find(query)?.groupValues?.getOrNull(1)
            ?.let { cleanTitleToken(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { out[SlotKeys.Attendee] = it.replaceFirstChar(Char::uppercaseChar) }

        extractDuration(query.lowercase())?.let { out[SlotKeys.DurationMinutes] = it }

        return out
    }

    /** "for one hour" / "30 minute meeting" / "half hour" → minutes. */
    private fun extractDuration(lower: String): Int? {
        if (HALF_HOUR.containsMatchIn(lower)) return 30
        HOURS.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: WORD_NUMBERS[m.groupValues[1]] ?: return@let
            return n * 60
        }
        MINUTES.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: WORD_NUMBERS[m.groupValues[1]] ?: return@let
            return n
        }
        return null
    }

    /** Title with no keyword: the words after the create-event lead-in, minus
     *  the "with <attendee>" clause and the trailing date/time clause (reusing
     *  the same [TRAILING_TIME] stripper as the keyword path, which already
     *  peels bare month/day tokens). Returns null when the lead-in is absent or
     *  the remainder is blank / a bare date. */
    private fun bareTitle(query: String): String? {
        val m = EVENT_LEAD_IN.find(query) ?: return null
        val candidate = query.substring(m.range.last + 1)
            .replace(LEADING_CONNECTOR, "")  // "for tomorrow …" — drop the date-intro preposition
            .replace(WITH_TAIL, "")
            .replace(TRAILING_TIME, "")
            .let { cleanTitleToken(it) }
            .trim()
        return candidate.takeIf { it.isNotBlank() && !looksLikePureDate(it) }
    }

    /** True when every token is a day/month word, a number word, an ordinal, or
     *  a clock — i.e. there's no actual title, just a date the trailing-time
     *  strip couldn't reach (a date sitting at the very start). */
    private fun looksLikePureDate(text: String): Boolean {
        val tokens = text.lowercase().split(MULTI_WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        return tokens.all { raw ->
            val t = raw.trim('.', ',', '!', '?', '\'', '"')
            t.isEmpty() || t in DAY_TOKENS || t in WORD_NUMBERS ||
                ORDINAL_TOKEN.matches(t) || BARE_CLOCK_TOKEN.matches(t)
        }
    }

    private companion object {
        // "… called standup" / "titled X" / "about X". Deliberately NOT
        // "for X" — "for tomorrow at 3" is a time clause, not a title.
        val TITLE = Regex("""(?i)\b(called|titled|about)\s+(.+)$""")
        // "with Alex" / "with the team" — stop before a trailing time/date
        // clause OR a title keyword ("with Alex called standup" → "Alex").
        // Trailing `[.!?,]*\s*$` lets "…with Alex." (dictation's trailing period)
        // still anchor the name, not just a bare end-of-string.
        val ATTENDEE = Regex("""(?i)\bwith\s+([A-Za-z][\w' ]*?)(?:\s+(?:on|at|tomorrow|today|tonight|next|this|called|titled|about)\b|[.!?,]*\s*$)""")
        // The shared trailing-time stripper (same gate as Reminder/Reschedule):
        // peels a real trailing time off a greedily-captured title, but keeps an
        // ordinary "in/at …" tail (codeberg #14). `allowBareDayToken` also peels
        // a bare trailing day word ("called standup tomorrow" → "standup").
        val TRAILING_TIME = trailingTimeClauseRegex(
            setOf("at", "on", "by", "in", "from", "next", "this"),
            allowBareDayToken = true,
        )

        val HALF_HOUR = Regex("""(?i)\bhalf (?:an )?hour\b""")
        val HOURS = Regex("""(?i)\b(\d{1,2}|one|two|three|four|five|six)\s*(?:hour|hr)s?\b""")
        val MINUTES = Regex("""(?i)\b(\d{1,3}|fifteen|thirty|forty five|forty-five|sixty)\s*(?:minute|min)s?\b""")

        // --- Bare-title (no keyword) support ---------------------------------
        // The create-event verb phrase, stripped before the title is read.
        val EVENT_LEAD_IN = Regex(
            """(?i)^\s*(?:create|schedule|set\s*up|setup|add|make|put|book|plan|new)\s+""" +
                """(?:a\s+|an\s+|my\s+)?(?:new\s+)?(?:event|meeting|appointment)\b\s*""",
        )
        // A date-introducing preposition at the very start ("for tomorrow",
        // "on friday") — there's no title before it, so drop it.
        val LEADING_CONNECTOR = Regex("""(?i)^\s*(?:for|on|at|in|by|from)\s+""")
        // "with <attendee> …" tail — the attendee is captured separately, and it
        // (plus anything after) isn't part of the title.
        val WITH_TAIL = Regex("""(?i)\s+with\s+\S.*$""")
        // A single ordinal token, word ("ninth", "thirtieth") or digit ("29th").
        val ORDINAL_TOKEN = Regex(
            """(?i)\d{1,2}(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|""" +
                """tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|""" +
                """eighteenth|nineteenth|twentieth|thirtieth""",
        )
        // A bare clock token ("3", "3pm", "3:30", "3:30pm").
        val BARE_CLOCK_TOKEN = Regex("""(?i)\d{1,2}(?:[:.]\d{2})?(?:a\.?m\.?|p\.?m\.?)?""")
    }
}

/** Type-safe slot reads for the CreateEvent handler. */
fun Map<String, Any>.eventTime(): Instant? = this[SlotKeys.Time] as? Instant
fun Map<String, Any>.eventTitle(): String? = this[SlotKeys.Title] as? String
fun Map<String, Any>.eventAttendee(): String? = this[SlotKeys.Attendee] as? String
fun Map<String, Any>.eventDurationMinutes(default: Int = 60): Int =
    (this[SlotKeys.DurationMinutes] as? Int) ?: default
