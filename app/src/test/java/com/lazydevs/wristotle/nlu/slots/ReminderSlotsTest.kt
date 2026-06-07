// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ReminderSlotsTest {

    private fun slots(query: String): Map<String, Any> = runBlocking {
        ReminderSlots().extract(query)
    }

    private fun title(query: String): String? = slots(query)["title"] as String?

    @Test fun `basic title with time stripped`() {
        assertEquals("Call mom", title("remind me to call mom at 2pm"))
    }

    // Regression: the time-strip regex matched the "at" inside "chat"/"that"
    // (missing word boundary), truncating titles like "chat with bob" → "ch".
    @Test fun `word containing at is not truncated`() {
        assertEquals("Chat with bob", title("remind me to chat with bob at 5pm"))
    }

    @Test fun `word containing in is not truncated`() {
        // "finish" contains "in" — must not be stripped as a time preposition.
        assertEquals("Finish the report", title("remind me to finish the report by 6pm"))
    }

    // When the user doesn't say a time, the slot defaults to roughly
    // 30 min from now instead of leaving the time null — handler used to
    // return "Couldn't understand the time" which surfaced as a dead end.
    // Wide tolerance (25-35 min) because the test clock drifts between
    // capturing 'now' and the extractor doing the same.
    @Test fun `missing time defaults to about 30 minutes from now`() {
        val before = System.currentTimeMillis()
        val time = slots("remind me to buy milk")["time"] as? Date
        val after = System.currentTimeMillis()

        assertNotNull("expected default time slot to be populated", time)
        val offsetMin = (time!!.time - before) / 60_000.0
        val maxOffsetMin = (time.time - after) / 60_000.0
        assertTrue(
            "expected ~30 min offset, got $offsetMin..$maxOffsetMin",
            offsetMin in 29.5..30.5 && maxOffsetMin in 29.5..30.5,
        )
    }

    @Test fun `missing time still extracts title`() {
        assertEquals("Buy milk", title("remind me to buy milk"))
    }

    // Issue #4: "remind me in two hours to <task>" — the leading time
    // clause sits between the prefix and the task, not at the end, so
    // the trailing-clause regex couldn't reach it. STRIP_LEADING_TIME_THEN_TO
    // now peels lead-in + duration + "to" before STRIP_TIME_PHRASES runs.
    @Test fun `leading duration clause before to is stripped`() {
        assertEquals(
            "Check the tables for the concessions.",
            title("remind me in two hours to check the tables for the concessions."),
        )
    }

    @Test fun `leading at-time clause before to is stripped`() {
        assertEquals("Send the email", title("remind me at noon to send the email"))
    }

    @Test fun `leading day-word clause before to is stripped`() {
        assertEquals("Call mom", title("remind me tomorrow to call mom"))
        assertEquals("Submit the form", title("remind me on monday to submit the form"))
        assertEquals("Pay the bill", title("remind me next week to pay the bill"))
    }

    // Make sure the new leading-clause regex doesn't fire when the
    // first word is a "to"-prefixed task — i.e. doesn't see "in" inside
    // "finish" or similar mid-word matches.
    @Test fun `task starting with non-lead-in word is unaffected`() {
        assertEquals("Buy groceries", title("remind me to buy groceries"))
        assertEquals("Check email", title("remind me to check email at 3pm"))
    }

    // --- persistent reminders ---

    @Test fun `plain reminder does not set persistent slot`() {
        assertNull("plain reminder must not be flagged persistent", slots("remind me to call mom at 2pm")["persistent"])
    }

    @Test fun `persistent reminder sets the flag and strips the modifier`() {
        val s = slots("persistent reminder to take meds at nine pm")
        assertEquals(true, s["persistent"])
        assertEquals("Take meds", s["title"])
    }

    @Test fun `nag me sets the flag and strips the prefix`() {
        val s = slots("nag me to drink water at noon")
        assertEquals(true, s["persistent"])
        assertEquals("Drink water", s["title"])
    }

    @Test fun `keep reminding me sets the flag and strips the prefix`() {
        val s = slots("keep reminding me to take the trash out at eight am")
        assertEquals(true, s["persistent"])
        assertEquals("Take the trash out", s["title"])
    }

    @Test fun `keep nagging me sets the flag and strips the prefix`() {
        val s = slots("keep nagging me to stretch at 3pm")
        assertEquals(true, s["persistent"])
        assertEquals("Stretch", s["title"])
    }

    @Test fun `remind me persistently shape strips modifier without leaving stray spaces`() {
        // "remind me persistently to call mom at 3pm" — modifier strip drops
        // "persistently" and the remaining "remind me  to" collapses through
        // STRIP_PREFIXES on the second pass.
        val s = slots("remind me persistently to call mom at 3pm")
        assertEquals(true, s["persistent"])
        assertEquals("Call mom", s["title"])
    }

    @Test fun `word containing persist is not flagged`() {
        // Defensive: "persistence" / "persisting" must not trip the detector.
        // The detector uses \b boundaries; "persistent" / "persistently" are
        // the only forms it accepts.
        assertNull(slots("remind me about persistence training at 4pm")["persistent"])
    }
}