// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.calendar

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Shared date/time formatting for the calendar handlers so the
 * watch-facing strings stay identical across read + create.
 *
 * Format outputs are short for the watch chat window:
 *   - [time]     → "3:00 PM"
 *   - [day]      → "Wed May 21"
 *   - [whenLabel] → "Wed May 21 at 3:00 PM" / "Wed May 21 (all day)"
 *
 * R4 batch 6 — was java.text.SimpleDateFormat, now kotlinx-datetime with
 * manual short-format string assembly. Output strings preserved exactly
 * so the watch chat copy doesn't change.
 */
object EventTimeFormat {

    /** "3:00 PM" — local wall clock. */
    fun time(epochMs: Long): String {
        val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return formatTime(ldt)
    }

    /** "Wed May 21" — local wall clock. */
    fun day(epochMs: Long): String {
        val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return formatDay(ldt)
    }

    /** "Wed May 21 at 3:00 PM", or "Wed May 21 (all day)" when [allDay]. */
    fun whenLabel(beginEpochMs: Long, allDay: Boolean = false): String {
        val ldt = Instant.fromEpochMilliseconds(beginEpochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return if (allDay) "${formatDay(ldt)} (all day)" else "${formatDay(ldt)} at ${formatTime(ldt)}"
    }

    private fun formatTime(ldt: LocalDateTime): String {
        val hour24 = ldt.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        val mm = ldt.minute.toString().padStart(2, '0')
        return "$hour12:$mm $ampm"
    }

    private fun formatDay(ldt: LocalDateTime): String {
        val weekday = WEEKDAY[ldt.dayOfWeek.ordinal]
        val month = MONTH[ldt.month.ordinal]
        return "$weekday $month ${ldt.dayOfMonth}"
    }

    private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTH = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}
