// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Clock
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
 * Goes through [resolveClockTime] like every other time intent, so a bare
 * "cancel the 8 alarm" at 2:30 PM resolves to 8 PM (hour 20) — matching where an
 * alarm "for 8" would have been set — rather than 8 AM. Explicit "8 am" keeps
 * that hour.
 *
 * R2 batch 4 — takes a [TimeParser] via constructor (lifted from :app's
 * top-level parseTime).
 */
class CancelAlarmSlots(
    private val timeParser: TimeParser,
    private val clock: Clock = Clock.System,
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val instant: Instant = timeParser.resolveClockTime(query, clock) ?: return emptyMap()
        return mapOf(SlotKeys.Time to instant)
    }
}
