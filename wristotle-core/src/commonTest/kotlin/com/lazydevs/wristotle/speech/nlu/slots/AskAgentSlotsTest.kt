// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AskAgentSlots' own behaviour (the trigger-regex itself is covered by
 * AskAgentTriggersTest): the `body.isBlank() → emptyMap()` branch — a lead-in
 * with no question must NOT produce an empty Query slot — and that custom
 * trigger words from [extrasProvider] are applied.
 */
class AskAgentSlotsTest {

    @Test fun leadInWithNoQuestionYieldsEmptyMap() = runTest {
        val slots = AskAgentSlots()
        // stripRegex requires whitespace after the lead-in, so the defensive
        // blank-body branch fires when the utterance is only the lead-in plus
        // trailing space — there's no question left, so no Query slot.
        assertTrue(slots.extract("ask agent ").isEmpty(), "'ask agent ' (no question)")
        assertTrue(slots.extract("hey agent  ").isEmpty(), "'hey agent  ' (no question)")
    }

    @Test fun bareLeadInWithoutTrailingSpaceIsNotStripped() = runTest {
        // No trailing whitespace → regex doesn't match → whole thing is the
        // query (documents that strip needs a separator before the body).
        val slots = AskAgentSlots()
        assertEquals("ask agent", slots.extract("ask agent")[SlotKeys.Query])
    }

    @Test fun strippedQuestionBecomesTheQuery() = runTest {
        val slots = AskAgentSlots()
        assertEquals("what's the weather", slots.extract("ask agent what's the weather")[SlotKeys.Query])
    }

    @Test fun customWakeWordStripsViaExtras() = runTest {
        val slots = AskAgentSlots(extrasProvider = { listOf("jarvis") })
        assertEquals(
            "what is my github profile?",
            slots.extract("Jarvis what is my github profile?")[SlotKeys.Query],
        )
    }

    @Test fun unregisteredWakeWordIsNotStripped() = runTest {
        // Without registering "jarvis", a bare wake-word is no trigger — the
        // whole utterance stays as the query (non-empty), proving extras matter.
        val slots = AskAgentSlots()
        assertEquals(
            "jarvis what's the time",
            slots.extract("jarvis what's the time")[SlotKeys.Query],
        )
    }
}
