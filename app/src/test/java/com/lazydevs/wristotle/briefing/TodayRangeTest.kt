package com.lazydevs.wristotle.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TodayRangeTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcNoon(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test fun `range starts at midnight and ends at next midnight`() {
        val now = utcNoon(2026, Calendar.JUNE, 5, hour = 14, minute = 30)
        val range = TodayRange.forDay(now, utc)
        assertEquals(utcNoon(2026, Calendar.JUNE, 5, hour = 0, minute = 0), range.startMs)
        assertEquals(utcNoon(2026, Calendar.JUNE, 6, hour = 0, minute = 0), range.endExclusiveMs)
    }

    @Test fun `contains is half-open`() {
        val range = TodayRange.forDay(utcNoon(2026, Calendar.JUNE, 5), utc)
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
