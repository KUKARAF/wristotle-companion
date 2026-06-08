// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ListReminders].
 *
 * Optional `time` (`kotlinx.datetime.Instant`): when the query names a
 * time ("is there a reminder at 2pm"), the handler answers for that
 * time instead of listing everything. Absent for a plain "what are my
 * reminders".
 *
 * R2 batch 5 — lifted from :app; [TimeParser] injected via constructor
 * (same pattern as ReminderSlots / CalendarSlots / SetAlarmSlots from
 * batch 4).
 */
class ListRemindersSlots(
    private val timeParser: TimeParser,
) : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> {
        val time = timeParser.parse(query)?.instant ?: return emptyMap()
        return mapOf(SlotKeys.Time to time)
    }
}
