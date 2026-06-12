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

    private companion object {
        // "… called standup" / "titled X" / "about X". Deliberately NOT
        // "for X" — "for tomorrow at 3" is a time clause, not a title.
        val TITLE = Regex("""(?i)\b(called|titled|about)\s+(.+)$""")
        // "with Alex" / "with the team" — stop before a trailing time/date
        // clause OR a title keyword ("with Alex called standup" → "Alex").
        // Trailing `[.!?,]*\s*$` lets "…with Alex." (dictation's trailing period)
        // still anchor the name, not just a bare end-of-string.
        val ATTENDEE = Regex("""(?i)\bwith\s+([A-Za-z][\w' ]*?)(?:\s+(?:on|at|tomorrow|today|tonight|next|this|called|titled|about)\b|[.!?,]*\s*$)""")
        // A trailing " at/on/… <rest>" clause to peel off a greedily-captured
        // title. The leading \s+ means a title-initial keyword ("next steps")
        // is left intact.
        val TRAILING_TIME = Regex("""(?i)\s+(?:at|on|by|in|from|tomorrow|today|tonight|next|this)\b.*$""")

        val HALF_HOUR = Regex("""(?i)\bhalf (?:an )?hour\b""")
        val HOURS = Regex("""(?i)\b(\d{1,2}|one|two|three|four|five|six)\s*(?:hour|hr)s?\b""")
        val MINUTES = Regex("""(?i)\b(\d{1,3}|fifteen|thirty|forty five|forty-five|sixty)\s*(?:minute|min)s?\b""")
    }
}

/** Type-safe slot reads for the CreateEvent handler. */
fun Map<String, Any>.eventTime(): Instant? = this[SlotKeys.Time] as? Instant
fun Map<String, Any>.eventTitle(): String? = this[SlotKeys.Title] as? String
fun Map<String, Any>.eventAttendee(): String? = this[SlotKeys.Attendee] as? String
fun Map<String, Any>.eventDurationMinutes(default: Int = 60): Int =
    (this[SlotKeys.DurationMinutes] as? Int) ?: default
