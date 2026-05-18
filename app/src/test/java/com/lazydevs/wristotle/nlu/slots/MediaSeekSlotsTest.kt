package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSeekSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { MediaSeekSlots().extract(query) }

    // --- Digit-form durations ---------------------------------------

    @Test fun `digit + seconds`() {
        assertEquals(30, extract("skip ahead 30 seconds")["seconds"])
    }

    @Test fun `digit + secs shorthand`() {
        assertEquals(15, extract("rewind 15 secs")["seconds"])
    }

    @Test fun `bare digit defaults to seconds`() {
        assertEquals(45, extract("skip ahead 45")["seconds"])
    }

    @Test fun `digit + minutes multiplies by 60`() {
        assertEquals(120, extract("go back 2 minutes")["seconds"])
    }

    @Test fun `digit + hours multiplies by 3600`() {
        assertEquals(3600, extract("skip ahead 1 hour")["seconds"])
    }

    // --- Word-form durations ----------------------------------------

    @Test fun `word number + seconds`() {
        assertEquals(10, extract("rewind ten seconds")["seconds"])
    }

    @Test fun `word number twenty + seconds`() {
        assertEquals(20, extract("skip forward twenty seconds")["seconds"])
    }

    @Test fun `a minute resolves to 60`() {
        assertEquals(60, extract("go back a minute")["seconds"])
    }

    @Test fun `two minutes resolves to 120`() {
        assertEquals(120, extract("skip forward two minutes")["seconds"])
    }

    // --- No duration → empty map (handler applies default) ----------

    @Test fun `no duration returns empty map`() {
        // Handler falls back to DEFAULT_FORWARD_S / DEFAULT_BACK_S.
        assertTrue(extract("rewind").isEmpty())
    }

    @Test fun `fast forward without number returns empty map`() {
        assertTrue(extract("fast forward").isEmpty())
    }

    @Test fun `query with no number words returns empty map`() {
        // No digit, no word in WORD_NUMBERS — handler falls back to
        // the default forward/back constants.
        assertTrue(extract("rewind for ages").isEmpty())
    }
}
