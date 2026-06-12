// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Clock

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ListReminders].
 *
 * Optional `time` (`kotlinx.datetime.Instant`): when the query names a
 * time ("is there a reminder at 2pm"), the handler answers for that
 * time instead of listing everything. Absent for a plain "what are my
 * reminders".
 *
 * The named time is rolled to its next occurrence the same way the
 * scheduling intents do — the handler matches reminders by hour-of-day, so
 * a bare "is there a reminder at 8" at 2:30 PM must resolve to 8 PM (where a
 * reminder set "at 8" would be), not 8 AM. Explicit "at 8 am" keeps that hour.
 *
 * R2 batch 5 — lifted from :app; [TimeParser] injected via constructor
 * (same pattern as ReminderSlots / CalendarSlots / SetAlarmSlots from
 * batch 4).
 */
class ListRemindersSlots(
    private val timeParser: TimeParser,
    private val clock: Clock = Clock.System,
) : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> {
        val time = timeParser.resolveClockTime(query, clock) ?: return emptyMap()
        return mapOf(SlotKeys.Time to time)
    }
}
