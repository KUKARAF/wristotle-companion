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
}
