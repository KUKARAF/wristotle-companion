package com.lazydevs.wristotle.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // --- past-due excluded ---

    @Test fun pastDueReminderNotMatched() {
        val records = listOf(rec("a", "Gym", offsetHours = -2))
        assertNull(ReminderMatching.bestMatch("gym", records, now))
    }

    // --- empty / no-target ---

    @Test fun emptyTargetReturnsNull() {
        val records = listOf(rec("a", "Gym"))
        assertNull(ReminderMatching.bestMatch("", records, now))
        assertNull(ReminderMatching.bestMatch("   ", records, now))
    }

    private fun dateAt(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)!!.time
}
