// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.reminders.ReminderMatching
import com.lazydevs.wristotle.speech.nlu.reminders.mentionsCalendarEvent
import com.lazydevs.wristotle.speech.nlu.reminders.mentionsTimer
import com.lazydevs.wristotle.speech.nlu.reminders.ReminderRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ReminderMatchingTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    private fun rec(id: String, title: String, offsetHours: Long = 1) =
        ReminderRecord(id, title, now + offsetHours * hour)

    // --- title matches ---

    @Test fun exactTitleTokenMatches() {
        val records = listOf(rec("a", "Gym"), rec("b", "Call dentist"))
        assertEquals("a", ReminderMatching.bestMatch("gym", records, now)?.id)
    }

    @Test fun tokenWithinMultiWordTitleMatches() {
        val records = listOf(rec("a", "Gym"), rec("b", "Call dentist"))
        assertEquals("b", ReminderMatching.bestMatch("dentist", records, now)?.id)
    }

    @Test fun prefixOfTitleTokenMatches() {
        val records = listOf(rec("a", "Dentist appointment"))
        assertEquals("a", ReminderMatching.bestMatch("dent", records, now)?.id)
    }

    @Test fun unrelatedTargetMatchesNothing() {
        val records = listOf(rec("a", "Gym"), rec("b", "Call dentist"))
        assertNull(ReminderMatching.bestMatch("groceries", records, now))
    }

    @Test fun partialTwoTokenTargetBelowFloorRejected() {
        // "call mom" vs "Call dentist": only 1 of 2 tokens lands (0.5) < floor.
        val records = listOf(rec("a", "Call dentist"))
        assertNull(ReminderMatching.bestMatch("call mom", records, now))
    }

    // --- time matches ---

    @Test fun clockTimeTargetMatches() {
        // Build a reminder at a known wall-clock time, then target that hour.
        val fivePm = dateAt("2001-09-09 17:00")
        val records = listOf(ReminderRecord("a", "Anonymous", fivePm))
        assertEquals("a", ReminderMatching.bestMatch("5pm", records, fivePm - hour)?.id)
        assertEquals("a", ReminderMatching.bestMatch("5 pm", records, fivePm - hour)?.id)
    }

    // --- retention window: recently fired matchable, old not ---

    @Test fun recentlyFiredReminderStillMatched() {
        // Fired 2h ago — still within the 24h retention window, so targetable.
        val records = listOf(rec("a", "Gym", offsetHours = -2))
        assertEquals("a", ReminderMatching.bestMatch("gym", records, now)?.id)
    }

    @Test fun reminderBeyondRetentionNotMatched() {
        val records = listOf(rec("a", "Gym", offsetHours = -26))
        assertNull(ReminderMatching.bestMatch("gym", records, now))
    }

    // --- empty / no-target ---

    @Test fun emptyTargetReturnsNull() {
        val records = listOf(rec("a", "Gym"))
        assertNull(ReminderMatching.bestMatch("", records, now))
        assertNull(ReminderMatching.bestMatch("   ", records, now))
    }

    // --- mentionsCalendarEvent (guard for "push my meeting") ---

    @Test fun calendarEventNounsDetected() {
        assertTrue(mentionsCalendarEvent("meeting"))
        assertTrue(mentionsCalendarEvent("my 3pm meeting"))
        assertTrue(mentionsCalendarEvent("the standup appointment"))
        assertTrue(mentionsCalendarEvent("dentist event"))
    }

    @Test fun reminderTargetsNotFlaggedAsCalendar() {
        assertFalse(mentionsCalendarEvent("gym"))
        assertFalse(mentionsCalendarEvent("call mom"))
        assertFalse(mentionsCalendarEvent("5pm"))
    }

    // --- mentionsTimer (guard for "cancel timer" platform dead-end) ---

    @Test fun timerNounsDetected() {
        assertTrue(mentionsTimer("timer"))
        assertTrue(mentionsTimer("the timer"))
        assertTrue(mentionsTimer("countdown"))
        assertTrue(mentionsTimer("my stopwatch"))
    }

    @Test fun reminderTargetsNotFlaggedAsTimer() {
        assertFalse(mentionsTimer("gym"))
        assertFalse(mentionsTimer("call mom"))
        assertFalse(mentionsTimer("5pm"))
    }

    private fun dateAt(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)!!.time
}