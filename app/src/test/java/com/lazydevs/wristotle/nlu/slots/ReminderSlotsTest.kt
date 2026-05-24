package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
