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

class CreateEventSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { CreateEventSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser).extract(query) }

    // --- Title (called / titled / about) --------------------------------

    @Test fun `title trailing the time is captured`() {
        // Greedy capture would grab "standup at two pm"; the trailing-time
        // strip peels the clock back off.
        assertEquals("Standup", extract("new event friday at two pm called standup")["title"])
    }

    @Test fun `title leading the time is captured`() {
        assertEquals("Standup", extract("schedule a meeting called standup at three pm")["title"])
    }

    @Test fun `titled keyword works`() {
        assertEquals("Budget review", extract("add a meeting titled budget review tomorrow at 3")["title"])
    }

    @Test fun `about keyword works`() {
        assertEquals("Roadmap", extract("schedule a meeting about roadmap at noon")["title"])
    }

    @Test fun `leading article is stripped from the title`() {
        assertEquals("Standup", extract("create a meeting called the standup at 3pm")["title"])
    }

    @Test fun `'for' is not a title keyword`() {
        // "for tomorrow at 3" is a time clause, not a title — must not become one.
        assertNull(extract("schedule a meeting for tomorrow at 3pm")["title"])
    }

    // codeberg #14 (meeting variant): a non-time "in …" in the title must
    // survive; only the genuine trailing time is peeled.
    @Test fun `non-time in-clause survives in the event title`() {
        assertEquals(
            "Put in an order",
            extract("schedule a meeting titled put in an order at 3pm")["title"],
        )
        assertEquals(
            "Walk in interviews",
            extract("create an event called walk in interviews on friday")["title"],
        )
    }

    @Test fun `no title keyword yields no title slot`() {
        assertNull(extract("schedule a meeting tomorrow at three pm")["title"])
    }

    // --- Attendee (with X) ----------------------------------------------

    @Test fun `attendee stops before the time clause`() {
        assertEquals("Alex", extract("create a meeting with Alex on friday at noon")["attendee"])
    }

    @Test fun `attendee article is stripped`() {
        assertEquals("Team", extract("schedule a call with the team next monday at ten")["attendee"])
    }

    @Test fun `attendee runs to end when no time follows`() {
        assertEquals("Sam", extract("book a meeting with Sam")["attendee"])
    }

    // On-device report: "schedule a meeting at 3 p.m. with Alex." dropped Alex
    // because dictation's trailing period broke the end-of-string anchor.
    @Test fun `attendee after the time clause is captured despite trailing period`() {
        assertEquals("Alex", extract("schedule a meeting at 3 p.m. with Alex.")["attendee"])
        assertEquals("Alex", extract("schedule a meeting at 3 p.m. with Alex")["attendee"])
    }

    @Test fun `no attendee yields no slot`() {
        assertNull(extract("schedule a meeting tomorrow at three pm")["attendee"])
    }

    // --- Duration --------------------------------------------------------

    @Test fun `word-form hours resolve to minutes`() {
        assertEquals(60, extract("add a one hour meeting tomorrow at eleven")["durationMinutes"])
    }

    @Test fun `digit-form hours resolve to minutes`() {
        assertEquals(120, extract("schedule a 2 hour meeting tomorrow at nine")["durationMinutes"])
    }

    @Test fun `digit minutes parse directly`() {
        assertEquals(45, extract("book a 45 minute meeting tomorrow at noon")["durationMinutes"])
    }

    @Test fun `half hour resolves to 30`() {
        assertEquals(30, extract("schedule a half hour meeting at 3pm")["durationMinutes"])
    }

    @Test fun `half an hour resolves to 30`() {
        assertEquals(30, extract("schedule a half an hour meeting at 3pm")["durationMinutes"])
    }

    @Test fun `no duration yields no slot`() {
        // Handler applies the 60-minute default.
        assertNull(extract("schedule a meeting tomorrow at three pm")["durationMinutes"])
    }

    // --- Time ------------------------------------------------------------

    @Test fun `a spoken time populates the time slot`() {
        assertNotNull(extract("schedule a meeting tomorrow at three pm")["time"])
    }

    // A bare clock time that already passed today must roll to the next day so
    // the event isn't created in the past (same fix as reminder issue #13).
    // Deterministic via a fake parser + fixed clock.
    @Test fun `past clock time rolls forward to next day`() {
        val now = Instant.parse("2026-06-12T20:00:00Z")
        val oneAmToday = Instant.parse("2026-06-12T01:00:00Z")
        val time = runBlocking {
            CreateEventSlots(
                timeParser = object : TimeParser {
                    override fun parse(query: String): ParsedTime? = ParsedTime(oneAmToday, "")
                },
                clock = object : Clock { override fun now(): Instant = now },
            ).extract("schedule a meeting at one am")["time"] as? Instant
        }
        assertEquals(Instant.parse("2026-06-13T01:00:00Z"), time)
    }

    // --- Combined --------------------------------------------------------

    @Test fun `attendee and title coexist`() {
        val r = extract("schedule a meeting with Alex called standup tomorrow at 3pm")
        assertEquals("Alex", r["attendee"])
        assertEquals("Standup", r["title"])
        assertNotNull(r["time"])
    }
}