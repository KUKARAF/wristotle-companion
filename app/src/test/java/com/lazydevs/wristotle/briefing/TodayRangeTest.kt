// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.briefing

import com.lazydevs.wristotle.speech.nlu.briefing.TodayRange
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayRangeTest {

    private val utc = TimeZone.UTC

    private fun utcNoon(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime(year, month, day, hour, minute, 0).toInstant(utc).toEpochMilliseconds()

    @Test fun `range starts at midnight and ends at next midnight`() {
        val now = utcNoon(2026, 6, 5, hour = 14, minute = 30)
        val range = TodayRange.forDay(now, utc)
        assertEquals(utcNoon(2026, 6, 5, hour = 0, minute = 0), range.startMs)
        assertEquals(utcNoon(2026, 6, 6, hour = 0, minute = 0), range.endExclusiveMs)
    }

    @Test fun `contains is half-open`() {
        val range = TodayRange.forDay(utcNoon(2026, 6, 5), utc)
        assertTrue(range.startMs in range)
        assertFalse(range.endExclusiveMs in range)  // exclusive end
        assertTrue(range.startMs + 1 in range)
    }

    @Test fun `now uses default zone`() {
        // Smoke: confirm the static factory wires through without arg.
        val r = TodayRange.now()
        assertTrue(r.endExclusiveMs > r.startMs)
    }
}
