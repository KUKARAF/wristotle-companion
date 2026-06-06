package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Slot-extractor tests for the SetAlarm intent. Verifies the
 * prefix-strip + parseTime flow for the common voice creation shapes
 * — `"set an alarm for 7am"` (absolute time) and `"set an alarm for
 * an hour from now"` (relative; codeberg
 * wristotle/wristotle-companion#8).
 */
class SetAlarmSlotsTest {

    private val slots = SetAlarmSlots()

    private fun extractDate(query: String): Date? = runBlocking {
        slots.extract(query)[SlotKeys.Time] as? Date
    }

    @Test fun `absolute 7am parses to 7 o'clock`() {
        val date = extractDate("set an alarm for 7am")
        assertNotNull("expected non-null date for 7am", date)
        val cal = Calendar.getInstance().apply { time = date!! }
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test fun `colon time parses`() {
        val date = extractDate("set an alarm for 6:30")
        assertNotNull(date)
        val cal = Calendar.getInstance().apply { time = date!! }
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test fun `an hour from now sets alarm one hour ahead`() {
        // Closes wristotle-companion#8. Prior to the fix, prettytime
        // anchored on the verb phrase "set an alarm for" and returned
        // the current time instead of +1h. Two-part fix: strip the
        // creation prefix BEFORE parseTime (this slot extractor), and
        // rewrite "an hour" → "1 hour" so prettytime can quantify the
        // article (TimeParser.normalizeNumbers).
        val before = System.currentTimeMillis()
        val date = extractDate("set an alarm for an hour from now")
        assertNotNull("expected non-null date for relative", date)
        val ms = date!!.time - before
        assertTrue(
            "expected ~1h ahead, got ${ms / 60000}min (delta ${ms}ms)",
            ms in 55 * 60 * 1000L..65 * 60 * 1000L,
        )
    }

    @Test fun `in thirty minutes sets alarm thirty minutes ahead`() {
        val before = System.currentTimeMillis()
        val date = extractDate("set an alarm in thirty minutes")
        assertNotNull(date)
        val ms = date!!.time - before
        assertTrue(
            "expected ~30min ahead, got ${ms / 60000}min",
            ms in 29 * 60 * 1000L..31 * 60 * 1000L,
        )
    }
}
