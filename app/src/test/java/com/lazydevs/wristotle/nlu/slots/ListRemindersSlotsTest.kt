// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.ParsedTime
import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slots.*

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListRemindersSlotsTest {

    private fun timeSlot(parsed: Instant?, now: Instant, query: String): Instant? = runBlocking {
        ListRemindersSlots(
            timeParser = object : TimeParser {
                override fun parse(query: String): ParsedTime? = parsed?.let { ParsedTime(it, "") }
            },
            clock = object : Clock { override fun now(): Instant = now },
        ).extract(query)["time"] as? Instant
    }

    // On-device report: at 2:30 PM, "is there a reminder at 8" answered "around
    // 8 AM" and missed the user's 8 PM reminders. The list matches by hour, so a
    // bare "8" must resolve to the next 8 o'clock (8 PM), like scheduling does.
    @Test fun `bare hour query rolls to the next occurrence`() {
        val now = Instant.parse("2026-06-12T14:30:00Z")
        val eightAmToday = Instant.parse("2026-06-12T08:00:00Z")
        assertEquals(
            Instant.parse("2026-06-12T20:00:00Z"),
            timeSlot(eightAmToday, now, "is there a reminder at 8"),
        )
    }

    @Test fun `explicit am query keeps the am hour`() {
        val now = Instant.parse("2026-06-12T14:30:00Z")
        val eightAmToday = Instant.parse("2026-06-12T08:00:00Z")
        // Rolls to tomorrow 8 AM (date moves, hour preserved) — the handler only
        // reads the hour, so it still matches 8 AM reminders.
        assertEquals(
            Instant.parse("2026-06-13T08:00:00Z"),
            timeSlot(eightAmToday, now, "is there a reminder at 8 am"),
        )
    }

    @Test fun `no time yields no slot`() {
        val now = Instant.parse("2026-06-12T14:30:00Z")
        assertNull(timeSlot(null, now, "what are my reminders"))
    }
}
