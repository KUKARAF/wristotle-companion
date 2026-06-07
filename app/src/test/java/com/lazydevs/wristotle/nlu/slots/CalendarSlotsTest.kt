// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { CalendarSlots().extract(query) }

    // --- Count ----------------------------------------------------------

    @Test fun `digit count is parsed`() {
        assertEquals(3, extract("what are my next 3 meetings")["count"])
    }

    @Test fun `word count is parsed`() {
        assertEquals(3, extract("what are my next three meetings")["count"])
    }

    @Test fun `word count five appointments`() {
        assertEquals(5, extract("show me my next five appointments")["count"])
    }

    @Test fun `singular next meeting yields no count slot`() {
        // Handler defaults to 1.
        assertNull(extract("when is my next meeting")["count"])
    }

    // --- Date hint gating -----------------------------------------------

    @Test fun `a named day populates the date slot`() {
        assertNotNull(extract("do I have anything on friday")["date"])
    }

    @Test fun `tomorrow populates the date slot`() {
        assertNotNull(extract("what's on my calendar tomorrow")["date"])
    }

    @Test fun `month name populates the date slot`() {
        assertNotNull(extract("do I have a meeting on may twenty fifth")["date"])
    }

    @Test fun `a bare count does not become a date`() {
        // The anti-greedy guarantee: "next 3" must not be parsed as a date.
        assertNull(extract("what are my next 3 meetings")["date"])
    }

    @Test fun `next meeting has no date`() {
        assertNull(extract("when is my next meeting")["date"])
    }
}