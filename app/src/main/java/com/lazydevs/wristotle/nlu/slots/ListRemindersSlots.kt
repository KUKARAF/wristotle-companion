// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import com.lazydevs.wristotle.speech.nlu.slots.*

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ListReminders].
 *
 * Optional `time` ([java.util.Date]): when the query names a time ("is there a
 * reminder at 2pm"), the handler answers for that time instead of listing
 * everything. Absent for a plain "what are my reminders".
 */
class ListRemindersSlots : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> {
        val time = parseTime(query)?.date ?: return emptyMap()
        return mapOf(SlotKeys.Time to time)
    }
}