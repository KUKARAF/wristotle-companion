// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.briefing

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Half-open epoch-millis range covering "today" in the device's local
 * time zone — [start, endExclusive). Pure (kotlinx-datetime, no Android
 * imports), so it's unit-testable without Robolectric and portable to
 * iOS via the same commonMain code.
 *
 * Used by the Morning Brief handler to bound every per-section read.
 * Deliberately a discrete since-midnight range — "rolling 24h forward"
 * would surface tomorrow's early events when called at 11pm, which
 * isn't what the user means by "today".
 *
 * Lifted from :app/briefing/TodayRange.kt in R2 batch 3 — internally
 * swapped from `java.util.{Calendar, TimeZone}` to kotlinx-datetime, but
 * the public `Long` epoch-millis API stays the same so handler callers
 * (still in :app) don't need to change.
 */
data class TodayRange(
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    operator fun contains(epochMs: Long): Boolean =
        epochMs in startMs until endExclusiveMs

    companion object {
        /** Today in the device's default time zone. */
        fun now(
            nowMs: Long = Clock.System.now().toEpochMilliseconds(),
            zone: TimeZone = TimeZone.currentSystemDefault(),
        ): TodayRange = forDay(nowMs, zone)

        /** Today in a specific zone — exposed for tests to pin the answer.
         *  Was `internal` in :app but had to widen when this lifted across
         *  the module boundary (R2 batch 3). */
        fun forDay(nowMs: Long, zone: TimeZone): TodayRange {
            val localDate: LocalDate = Instant.fromEpochMilliseconds(nowMs)
                .toLocalDateTime(zone)
                .date
            val startMs = localDate.atStartOfDayIn(zone).toEpochMilliseconds()
            val endMs = localDate.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
            return TodayRange(startMs = startMs, endExclusiveMs = endMs)
        }
    }
}
