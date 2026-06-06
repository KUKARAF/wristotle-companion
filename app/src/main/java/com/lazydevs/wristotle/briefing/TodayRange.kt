package com.lazydevs.wristotle.briefing

import java.util.Calendar
import java.util.TimeZone

/**
 * Half-open epoch-millis range covering "today" in the device's local
 * time zone — [start, endExclusive). Pure (Calendar + TimeZone are
 * `java.util`, no Android imports), so it's unit-testable without
 * Robolectric.
 *
 * Used by the Morning Brief handler to bound every per-section read.
 * Deliberately a discrete since-midnight range — "rolling 24h forward"
 * would surface tomorrow's early events when called at 11pm, which
 * isn't what the user means by "today".
 */
data class TodayRange(
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    operator fun contains(epochMs: Long): Boolean =
        epochMs in startMs until endExclusiveMs

    companion object {
        /** Today in the device's default time zone. */
        fun now(nowMs: Long = System.currentTimeMillis()): TodayRange =
            forDay(nowMs, TimeZone.getDefault())

        /** Today in a specific zone — exposed for tests to pin the answer. */
        internal fun forDay(nowMs: Long, zone: TimeZone): TodayRange {
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            return TodayRange(startMs = start, endExclusiveMs = cal.timeInMillis)
        }
    }
}
