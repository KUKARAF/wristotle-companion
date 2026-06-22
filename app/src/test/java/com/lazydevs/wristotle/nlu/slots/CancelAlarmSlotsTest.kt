// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.PrettyTimeTimeParser
import com.lazydevs.wristotle.speech.nlu.slots.CancelAlarmSlots
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Slot tests for CancelAlarm. The key boundary: a bare "cancel alarm" must
 * yield NO time slot (the handler reads the absence as "cancel ALL watch
 * alarms", epoch=0) — while a time-qualified phrasing must carry an Instant so
 * the handler cancels the matching alarm. Uses the real prettytime parser.
 *
 * Assertions are rollover-safe: an explicit-meridiem time ("7am") rolls by
 * whole days so its HOUR survives; a bare colon time ("6:30") can roll 12h, so
 * we assert only its preserved MINUTE.
 */
class CancelAlarmSlotsTest {

    private val slots = CancelAlarmSlots(PrettyTimeTimeParser)

    private fun extractEmpty(query: String): Boolean = runBlocking { slots.extract(query).isEmpty() }
    private fun extractTime(query: String): Date? = runBlocking {
        (slots.extract(query)[SlotKeys.Time] as? Instant)?.let { Date(it.toEpochMilliseconds()) }
    }

    @Test fun `bare cancel-alarm phrasings carry no time slot`() {
        assertTrue("\"cancel alarm\" should be empty", extractEmpty("cancel alarm"))
        assertTrue("\"stop the alarm\" should be empty", extractEmpty("stop the alarm"))
        assertTrue("\"cancel all alarms\" should be empty", extractEmpty("cancel all alarms"))
        assertTrue("\"turn off my alarm\" should be empty", extractEmpty("turn off my alarm"))
    }

    @Test fun `explicit 7am yields a 7 o'clock time slot`() {
        val d = extractTime("cancel the 7am alarm")
        assertNotNull("expected a time slot for an explicit hour", d)
        assertEquals(7, Calendar.getInstance().apply { time = d!! }.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun `colon time yields the right minute`() {
        val d = extractTime("stop the 6:30 alarm")
        assertNotNull(d)
        assertEquals(30, Calendar.getInstance().apply { time = d!! }.get(Calendar.MINUTE))
    }
}
