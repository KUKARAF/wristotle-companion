// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HomeAssistantSlots] behaviour — the lead-in-only → emptyMap() guard, the
 * happy strip path, and custom-wake-word application via extrasProvider. The
 * regex itself is covered by [HomeAssistantTriggersTest].
 */
class HomeAssistantSlotsTest {

    @Test fun leadInWithNoCommandYieldsEmptyMap() = runTest {
        val slots = HomeAssistantSlots()
        assertTrue(slots.extract("hey home assistant ").isEmpty(), "'hey home assistant ' (no command)")
        assertTrue(slots.extract("home assistant  ").isEmpty(), "'home assistant  ' (no command)")
    }

    @Test fun strippedCommandBecomesTheQuery() = runTest {
        val slots = HomeAssistantSlots()
        assertEquals(
            "turn off the kitchen lights",
            slots.extract("hey home assistant turn off the kitchen lights")[SlotKeys.Query],
        )
    }

    @Test fun tellToConnectorIsStripped() = runTest {
        val slots = HomeAssistantSlots()
        assertEquals(
            "lock the front door",
            slots.extract("tell home assistant to lock the front door")[SlotKeys.Query],
        )
    }

    @Test fun customWakeWordStripsViaExtras() = runTest {
        val slots = HomeAssistantSlots(extrasProvider = { listOf("jarvis") })
        assertEquals(
            "close the blinds",
            slots.extract("Jarvis close the blinds")[SlotKeys.Query],
        )
    }

    @Test fun unregisteredWakeWordIsNotStripped() = runTest {
        val slots = HomeAssistantSlots()
        assertEquals(
            "jarvis turn on the fan",
            slots.extract("jarvis turn on the fan")[SlotKeys.Query],
        )
    }
}
