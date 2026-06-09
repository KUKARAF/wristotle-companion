// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.reminders.ReminderListFormatter
import com.lazydevs.wristotle.speech.nlu.reminders.ReminderRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderListFormatterTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun emptyListReportsNone() {
        assertEquals("You have no reminders", ReminderListFormatter.format(emptyList(), now))
    }

    @Test fun remindersOlderThanRetentionAreDropped() {
        val records = listOf(ReminderRecord("a", "Old", now - day - hour))
        assertEquals("You have no reminders", ReminderListFormatter.format(records, now))
    }

    @Test fun recentlyFiredReminderWithinRetentionIsKept() {
        // Fired 2h ago — still listed (retention window is 24h).
        val records = listOf(ReminderRecord("a", "Gym", now - 2 * hour))
        assertTrue(ReminderListFormatter.format(records, now).contains("• Gym — "))
    }

    @Test fun singularHeaderForOne() {
        val records = listOf(ReminderRecord("a", "Gym", now + hour))
        val out = ReminderListFormatter.format(records, now)
        assertTrue(out.startsWith("You have 1 reminder:"))
        assertTrue(out.contains("• Gym — "))
    }

    @Test fun pluralHeaderForMany() {
        val records = listOf(
            ReminderRecord("a", "Gym", now + hour),
            ReminderRecord("b", "Call dentist", now + 2 * hour),
        )
        assertTrue(ReminderListFormatter.format(records, now).startsWith("You have 2 reminders:"))
    }

    @Test fun nullTimeReminderHasNoTimeSuffix() {
        val records = listOf(ReminderRecord("a", "Legacy", null))
        val out = ReminderListFormatter.format(records, now)
        assertTrue(out.contains("• Legacy"))
        assertFalse(out.contains("• Legacy — "))
    }

    @Test fun overflowCollapsesIntoAndMore() {
        val records = (1..7).map { ReminderRecord("id$it", "R$it", now + it * hour) }
        val out = ReminderListFormatter.format(records, now)
        assertTrue(out.startsWith("You have 7 reminders:"))
        assertTrue(out.contains("…and 2 more"))
        // Only the first 5 rows are shown.
        assertTrue(out.contains("• R5 — "))
        assertFalse(out.contains("• R6 — "))
    }

    @Test fun persistentRowGetsPersistentSuffix() {
        val records = listOf(
            ReminderRecord("a", "Take meds", now + hour, isPersistent = true, attemptsRemaining = 5),
            ReminderRecord("b", "Gym", now + 2 * hour),
        )
        val out = ReminderListFormatter.format(records, now)
        assertTrue(out.contains("• Take meds — ") && out.contains("(persistent)"))
        // The plain one stays plain — no spurious suffix.
        assertTrue(out.lines().any { it.startsWith("• Gym — ") && !it.contains("(persistent)") })
    }

    @Test fun persistentSuffixWithoutTime() {
        val records = listOf(
            ReminderRecord("a", "Legacy", null, isPersistent = true, attemptsRemaining = 5),
        )
        assertTrue(ReminderListFormatter.format(records, now).contains("• Legacy (persistent)"))
    }

    // --- formatAtTime: answer for a specific clock hour ---

    private fun atHour(dayOffset: Int, hourOfDay: Int): Long =
        java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test fun atTimeMatchesSameHour() {
        val twoPmToday = atHour(0, 14)
        val records = listOf(
            ReminderRecord("a", "Gym", twoPmToday),
            ReminderRecord("b", "Dentist", atHour(0, 15)),
        )
        val out = ReminderListFormatter.formatAtTime(records, twoPmToday, now = twoPmToday - hour)
        assertTrue(out.contains("• Gym — "))
        assertFalse(out.contains("Dentist"))
        assertTrue(out.startsWith("1 reminder around"))
    }

    @Test fun atTimeNoMatchReportsNone() {
        val records = listOf(ReminderRecord("a", "Gym", atHour(0, 15)))
        val out = ReminderListFormatter.formatAtTime(records, atHour(0, 14), now = atHour(0, 13))
        assertTrue(out.startsWith("No reminder around"))
    }

    @Test fun atTimeIncludesRecentlyFired() {
        // A 2pm reminder asked about at 4pm same day is still reported (within
        // the 24h retention window) — the fix for "no reminder at 2pm" at 5pm.
        val twoPm = atHour(0, 14)
        val records = listOf(ReminderRecord("a", "Gym", twoPm))
        val out = ReminderListFormatter.formatAtTime(records, twoPm, now = twoPm + 2 * hour)
        assertTrue(out.contains("• Gym — "))
    }

    @Test fun atTimeExcludesBeyondRetention() {
        // More than a day past → no longer reported.
        val twoPm = atHour(0, 14)
        val records = listOf(ReminderRecord("a", "Gym", twoPm))
        val out = ReminderListFormatter.formatAtTime(records, twoPm, now = twoPm + day + hour)
        assertTrue(out.startsWith("No reminder around"))
    }
}