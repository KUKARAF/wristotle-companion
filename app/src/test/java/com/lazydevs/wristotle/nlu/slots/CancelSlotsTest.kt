// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CancelSlotsTest {

    private fun target(query: String): String? = runBlocking {
        CancelSlots().extract(query)["target"] as String?
    }

    // --- bare cancels carry no target (handler → cancel latest) ---

    @Test fun `cancel that reminder has no target`() {
        assertNull(target("cancel that reminder"))
    }

    @Test fun `cancel my last reminder has no target`() {
        assertNull(target("cancel my last reminder"))
    }

    @Test fun `bare cancel it has no target`() {
        assertNull(target("cancel it"))
    }

    @Test fun `plain cancel has no target`() {
        assertNull(target("cancel"))
    }

    // --- named targets are extracted ---

    @Test fun `cancel the gym reminder extracts gym`() {
        assertEquals("gym", target("cancel the gym reminder"))
    }

    @Test fun `cancel my reminder about the dentist extracts dentist`() {
        assertEquals("dentist", target("cancel my reminder about the dentist"))
    }

    @Test fun `cancel my 5pm reminder extracts time`() {
        assertEquals("5pm", target("cancel my 5pm reminder"))
    }

    @Test fun `delete reminder to call mom extracts call mom`() {
        assertEquals("call mom", target("delete my reminder to call mom"))
    }

    // --- Whisper past-tense renderings reduce to bare-cancel ---

    @Test fun `cancelled past-tense reduces to bare cancel`() {
        // Whisper sometimes hears "Cancel the reminder." as "Cancelled
        // reminder." Pre-fix, "cancelled" survived stripping and became
        // the target — handler returned "No reminder matching 'cancelled'".
        assertNull(target("Cancelled reminder."))
    }

    @Test fun `canceled American-spelling reduces to bare cancel`() {
        assertNull(target("Canceled reminder."))
    }

    @Test fun `removed past-tense reduces to bare cancel`() {
        assertNull(target("Removed the reminder."))
    }
}