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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class RescheduleSlotsTest {

    private fun slots(query: String): Map<String, Any> = runBlocking {
        RescheduleSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser).extract(query)
    }

    private fun target(query: String) = slots(query)["target"] as String?
    // R2 batch 4 — slot value type changed Date → Instant. Convert at the
    // test boundary so the rest of this Date-using test body stays the same.
    private fun time(query: String) =
        (slots(query)["time"] as? kotlinx.datetime.Instant)?.let { Date(it.toEpochMilliseconds()) }

    // --- target extraction (time clause + verbs + fillers stripped) ---

    @Test fun `move my gym reminder to noon extracts gym`() {
        assertEquals("gym", target("move my gym reminder to noon"))
    }

    @Test fun `postpone the dentist reminder extracts dentist`() {
        assertEquals("dentist", target("postpone the dentist reminder to tomorrow"))
    }

    @Test fun `bare snooze with duration has no target`() {
        assertNull(target("snooze for ten minutes"))
    }

    @Test fun `push it back has no target`() {
        assertNull(target("push it back to eight pm"))
    }

    // --- time extraction ---

    @Test fun `relative time is parsed`() {
        assertNotNull(time("snooze for ten minutes"))
    }

    @Test fun `clock time is parsed`() {
        assertNotNull(time("reschedule my reminder to six pm"))
    }

    // --- past-time rollover (issue #13 family, reschedule) ---

    private fun rolledTime(parsed: Instant, now: Instant, query: String): Instant? = runBlocking {
        RescheduleSlots(
            timeParser = object : TimeParser {
                override fun parse(query: String): ParsedTime? = ParsedTime(parsed, "")
            },
            clock = object : Clock { override fun now(): Instant = now },
        ).extract(query)["time"] as? Instant
    }

    @Test fun `bare hour reschedule past this morning rolls to this evening`() {
        val now = Instant.parse("2026-06-12T09:00:00Z")
        val eightAmToday = Instant.parse("2026-06-12T08:00:00Z")
        assertEquals(
            Instant.parse("2026-06-12T20:00:00Z"),
            rolledTime(eightAmToday, now, "move my reminder to 8"),
        )
    }

    @Test fun `explicit am reschedule rolls to tomorrow`() {
        val now = Instant.parse("2026-06-12T09:00:00Z")
        val eightAmToday = Instant.parse("2026-06-12T08:00:00Z")
        assertEquals(
            Instant.parse("2026-06-13T08:00:00Z"),
            rolledTime(eightAmToday, now, "move my reminder to 8 am"),
        )
    }
}