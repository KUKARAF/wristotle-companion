// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Calendar]:
 *   - `count` — Int, how many upcoming events to list ("next 3 meetings").
 *               Absent → the handler defaults to 1 ("next meeting").
 *   - `date`  — [java.util.Date], set only when the query names a specific
 *               day ("on May 25", "tomorrow", "next monday"). When present
 *               the handler lists that day's events; when absent it lists
 *               the next [count] upcoming events.
 *
 * Date is parsed only when a day token is present so PrettyTime can't
 * greedily turn a bare count ("next 3") into a date.
 */
class CalendarSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        val lower = query.lowercase()

        extractCount(lower)?.let { out[SlotKeys.Count] = it }

        if (DATE_HINT.containsMatchIn(lower)) {
            parseTime(query)?.let { out[SlotKeys.Date] = it.date }
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
        // calendar" don't trigger a spurious date parse.
        val DATE_HINT = Regex(
            """(?i)\b(today|tonight|tomorrow|this (?:morning|afternoon|evening|week|weekend)|""" +
                """monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
                """january|february|march|april|may|june|july|august|september|october|november|december)\b"""
        )
    }
}

/** Type-safe slot reads for the Calendar handler. */
fun Map<String, Any>.calendarCount(default: Int = 1): Int = (this[SlotKeys.Count] as? Int) ?: default
fun Map<String, Any>.calendarDate(): Date? = this[SlotKeys.Date] as? Date