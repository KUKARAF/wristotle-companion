package com.lazydevs.wristotle.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderListFormatterTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    @Test fun emptyListReportsNone() {
        assertEquals("You have no reminders", ReminderListFormatter.format(emptyList(), now))
    }

    @Test fun pastDueRemindersAreDropped() {
        val records = listOf(ReminderRecord("a", "Old", now - hour))
        assertEquals("You have no reminders", ReminderListFormatter.format(records, now))
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
}
