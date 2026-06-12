// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Instant

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Calendar]:
 *   - `count` — Int, how many upcoming events to list ("next 3 meetings").
 *               Absent → the handler defaults to 1 ("next meeting").
 *   - `date`  — [Instant], set only when the query names a specific
 *               day ("on May 25", "tomorrow", "next monday"). When present
 *               the handler lists that day's events; when absent it lists
 *               the next [count] upcoming events.
 *
 * Date is parsed only when a day token is present so PrettyTime can't
 * greedily turn a bare count ("next 3") into a date.
 *
 * R2 batch 4 — lifted from :app, takes the [TimeParser] interface
 * via constructor rather than calling the JVM-only `parseTime` global.
 */
class CalendarSlots(
    private val timeParser: TimeParser,
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        val lower = query.lowercase()

        extractCount(lower)?.let { out[SlotKeys.Count] = it }

        if (DATE_HINT.containsMatchIn(lower)) {
            timeParser.parse(query)?.let { out[SlotKeys.Date] = it.instant }
        }
        return out
    }

    /** "next 3 meetings" / "my next five appointments" → 3 / 5. */
    private fun extractCount(lower: String): Int? {
        COUNT_DIGIT.find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        COUNT_WORD.find(lower)?.groupValues?.getOrNull(1)?.let { return WORD_NUMBERS[it] }
        return null
    }

    private companion object {
        val COUNT_DIGIT = Regex("""(?i)\bnext\s+(\d{1,2})\s+(?:meeting|appointment|event)""")
        val COUNT_WORD = Regex("""(?i)\bnext\s+(two|three|four|five|six|seven|eight|nine|ten)\s+(?:meeting|appointment|event)""")
        // Day tokens that justify parsing a concrete date. Deliberately
        // excludes bare "on"/"next" so counts and generic "what's on my
        // calendar" don't trigger a spurious date parse. The weekday/month/
        // relative-day vocabulary is the shared DAY_TOKENS; the "this …" phrases
        // are calendar-specific and stay here.
        val DATE_HINT = Regex(
            """(?i)\b(this (?:morning|afternoon|evening|week|weekend)|$DAY_TOKEN_ALT)\b"""
        )
    }
}

/** Type-safe slot reads for the Calendar handler. */
fun Map<String, Any>.calendarCount(default: Int = 1): Int = (this[SlotKeys.Count] as? Int) ?: default
fun Map<String, Any>.calendarDate(): Instant? = this[SlotKeys.Date] as? Instant
