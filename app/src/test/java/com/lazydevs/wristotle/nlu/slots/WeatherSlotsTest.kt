// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slots.*

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { WeatherSlots().extract(query) }

    // --- Location extraction (same shape as WorldTimeSlots) ----------

    @Test fun `single-word city`() {
        assertEquals("tokyo", extract("weather in tokyo")["location"])
    }

    @Test fun `multi-word city`() {
        assertEquals("new york", extract("what's the weather in new york")["location"])
    }

    @Test fun `with san francisco`() {
        assertEquals("san francisco", extract("what is the weather in san francisco")["location"])
    }

    // --- Trailing filler stripped ------------------------------------

    @Test fun `trailing right now is dropped`() {
        assertEquals("london", extract("what's the weather in london right now")["location"])
    }

    // --- Bare query → empty (handler decides what to do) -------------

    @Test fun `bare what is the weather yields no location`() {
        assertNull(extract("what's the weather")["location"])
    }

    @Test fun `bare weather yields no location`() {
        assertNull(extract("weather")["location"])
    }

    // --- Temporal in-phrase is NOT a place ---------------------------

    @Test fun `in the morning is not a location`() {
        assertNull(extract("is it warm in the morning")["location"])
    }
}