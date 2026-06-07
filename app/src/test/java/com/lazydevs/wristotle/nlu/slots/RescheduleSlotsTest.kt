// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class RescheduleSlotsTest {

    private fun slots(query: String): Map<String, Any> = runBlocking {
        RescheduleSlots().extract(query)
    }

    private fun target(query: String) = slots(query)["target"] as String?
    private fun time(query: String) = slots(query)["time"] as Date?

    // --- target extraction (time clause + verbs + fillers stripped) ---

    @Test fun `move my gym reminder to noon extracts gym`() {
        assertEquals("gym", target("move my gym reminder to noon"))
    }

    @Test fun `postpone the dentist reminder extracts dentist`() {
        assertEquals("dentist", target("postpone the dentist reminder to tomorrow"))
    }

    @Test fun `bare snooze with duration has no target`() {
        assertNull(target("snooze for ten minutes"))
    }

    @Test fun `push it back has no target`() {
        assertNull(target("push it back to eight pm"))
    }

    // --- time extraction ---

    @Test fun `relative time is parsed`() {
        assertNotNull(time("snooze for ten minutes"))
    }

    @Test fun `clock time is parsed`() {
        assertNotNull(time("reschedule my reminder to six pm"))
    }
}