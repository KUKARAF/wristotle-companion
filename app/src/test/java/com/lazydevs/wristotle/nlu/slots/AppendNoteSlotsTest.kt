package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppendNoteSlotsTest {

    private fun body(query: String): String? = runBlocking {
        AppendNoteSlots().extract(query)["body"] as String?
    }

    @Test fun `add to previous notes is stripped`() {
        assertEquals("Meeting moved to five", body("add to previous notes meeting moved to five"))
    }

    @Test fun `add to my previous note is stripped`() {
        assertEquals("Speaker is bob", body("add to my previous note speaker is bob"))
    }

    @Test fun `add to the last note is stripped`() {
        assertEquals("Alex is bringing snacks", body("add to the last note alex is bringing snacks"))
    }

    @Test fun `add to latest notes is stripped`() {
        assertEquals("Room changed", body("add to latest notes room changed"))
    }

    @Test fun `append to my note is stripped`() {
        assertEquals("Tracking number is 1234", body("append to my note tracking number is 1234"))
    }

    @Test fun `append alone is stripped`() {
        assertEquals("Door code is 9876", body("append door code is 9876"))
    }

    @Test fun `append with colon`() {
        assertEquals("Meeting is in room c", body("append: meeting is in room c"))
    }

    @Test fun `append with trailing period (whisper auto-punctuation)`() {
        assertEquals("Bring extra chairs", body("Append. Bring extra chairs"))
    }

    @Test fun `add to the previous note with that connector`() {
        assertEquals("Recipe needs salt", body("add to the previous note that recipe needs salt"))
    }
}
