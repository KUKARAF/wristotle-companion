package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldTimeSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { WorldTimeSlots().extract(query) }

    // --- Location extraction ----------------------------------------

    @Test fun `single-word city`() {
        assertEquals("tokyo", extract("what time is it in tokyo")["location"])
    }

    @Test fun `bare time-in form`() {
        assertEquals("london", extract("time in london")["location"])
    }

    @Test fun `multi-word city`() {
        assertEquals("new york", extract("what's the time in new york")["location"])
    }

    @Test fun `at preposition also works`() {
        assertEquals("paris", extract("what time is it at paris")["location"])
    }

    @Test fun `country name passes through for the resolver`() {
        assertEquals("india", extract("what time is it in india")["location"])
    }

    // --- Trailing filler is stripped --------------------------------

    @Test fun `trailing right now is dropped`() {
        assertEquals("los angeles", extract("what time is it in los angeles right now")["location"])
    }

    @Test fun `trailing please is dropped`() {
        assertEquals("berlin", extract("what time is it in berlin please")["location"])
    }

    // --- Non-locations and absent locations -------------------------

    @Test fun `no preposition yields empty`() {
        assertNull(extract("what time is it")["location"])
    }

    @Test fun `temporal in-phrase is not a location`() {
        // "in the morning" is a time-of-day, not a place.
        assertNull(extract("what time is it in the morning")["location"])
    }

    @Test fun `the in inside a word is not a preposition`() {
        // "raining" must not be read as "in <aining>".
        assertNull(extract("is it raining")["location"])
    }
}
