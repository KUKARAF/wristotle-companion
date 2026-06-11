// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.datetime.Instant

import com.lazydevs.wristotle.speech.nlu.slots.*

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

    private val slots = SetAlarmSlots(com.lazydevs.wristotle.handlers.PrettyTimeTimeParser)

    // R2 batch 4 — slot value is now Instant (commonMain). Convert to Date
    // at the test boundary so the existing Calendar-based assertions keep
    // working without rewriting every test body.
    private fun extractDate(query: String): Date? = runBlocking {
        (slots.extract(query)[SlotKeys.Time] as? Instant)?.let { Date(it.toEpochMilliseconds()) }
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

    @Test fun `compound relative duration sets alarm at the sum`() {
        // Closes codeberg #12 (regression report). Pre-fix this returned
        // "Couldn't understand the time" because prettytime-nlp can't sum
        // "one hour and four minutes". SetTimer handled it via the shared
        // parseDurationSeconds helper, SetAlarm now does the same.
        val before = System.currentTimeMillis()
        val date = extractDate("set an alarm for one hour and four minutes from now")
        assertNotNull("expected non-null date for compound duration", date)
        val ms = date!!.time - before
        // 1h4m = 64 minutes = 3,840,000 ms. Allow ±60s clock jitter.
        assertTrue(
            "expected ~64min ahead, got ${ms / 60000}min",
            ms in 63 * 60 * 1000L..65 * 60 * 1000L,
        )
    }

    @Test fun `pm with dots parses the same as pm`() {
        // Closes codeberg #12 (regression report). "7:51 p.m." used to
        // fail because the dots broke prettytime-nlp's AM/PM tokenizer.
        // The slot extractor now normalises "p.m." → "pm" before parsing.
        val date = extractDate("set an alarm for 7:51 p.m.")
        assertNotNull("expected non-null date for 7:51 p.m.", date)
        val cal = Calendar.getInstance().apply { time = date!! }
        assertEquals(19, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(51, cal.get(Calendar.MINUTE))
    }

    @Test fun `am with dots parses the same as am`() {
        val date = extractDate("set an alarm for 6:30 a.m.")
        assertNotNull(date)
        val cal = Calendar.getInstance().apply { time = date!! }
        assertEquals(6, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }
}