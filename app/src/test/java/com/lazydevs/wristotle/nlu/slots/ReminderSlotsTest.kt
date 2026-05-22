package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderSlotsTest {

    private fun title(query: String): String? = runBlocking {
        ReminderSlots().extract(query)["title"] as String?
    }

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
}
