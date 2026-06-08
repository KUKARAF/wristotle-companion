// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Instant

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.CancelAlarm].
 *
 * Returns an empty map for the bare *"cancel alarm"* / *"stop the alarm"*
 * phrasing — the handler interprets the missing slot as "cancel all watch
 * alarms" and sends epoch=0 to the watch.
 *
 * If a wall-clock time is parseable from the query (*"cancel 7am alarm"*,
 * *"stop the 6:30 alarm"*, *"dismiss alarm at 8pm"*), the slot carries an
 * [Instant] — the handler reads hour + minute off it to look up the
 * matching Room rows and cancel each one's watch leg by wireEpoch.
 *
 * R2 batch 4 — takes a [TimeParser] via constructor (lifted from :app's
 * top-level parseTime).
 */
class CancelAlarmSlots(
    private val timeParser: TimeParser,
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val instant: Instant = timeParser.parse(query)?.instant ?: return emptyMap()
        return mapOf(SlotKeys.Time to instant)
    }
}
