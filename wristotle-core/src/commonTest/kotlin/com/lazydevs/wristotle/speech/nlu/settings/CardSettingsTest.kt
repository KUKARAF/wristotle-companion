// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardSettingsTest {

    @Test
    fun `fresh install shows only sports, meetings and weather`() {
        val s = CardSettings(InMemoryKeyValueStore())
        assertTrue(s.isEnabled(CardSettings.SPORT_SCORE))
        assertTrue(s.isEnabled(CardSettings.MEETING_CREATE))
        assertTrue(s.isEnabled(CardSettings.WEATHER_CURRENT))
        // Everything else starts off.
        assertFalse(s.isEnabled(CardSettings.TASK_CREATE))
        assertFalse(s.isEnabled(CardSettings.REMINDER_CREATE))
        assertFalse(s.isEnabled(CardSettings.AGENT_ANSWER))
        assertEquals(CardSettings.DEFAULT_DISABLED, s.disabledKinds.value)
    }

    @Test
    fun `unknown future kinds pass through`() {
        val s = CardSettings(InMemoryKeyValueStore())
        assertTrue(s.isEnabled("some_future_kind"))
    }

    @Test
    fun `enabling an off-by-default kind persists across reloads`() {
        val store = InMemoryKeyValueStore()
        CardSettings(store).setEnabled(CardSettings.TASK_CREATE, on = true)
        assertTrue(CardSettings(store).isEnabled(CardSettings.TASK_CREATE))
    }

    @Test
    fun `hide all then show all, and explicit empty survives reload`() {
        val store = InMemoryKeyValueStore()
        val s = CardSettings(store)

        s.setEnabledKinds(emptySet())               // Hide all
        assertFalse(s.isEnabled(CardSettings.SPORT_SCORE))
        assertEquals(CardSettings.ALL_KINDS, s.disabledKinds.value)

        s.setEnabledKinds(CardSettings.ALL_KINDS)   // Show all
        assertTrue(s.isEnabled(CardSettings.SPORT_SCORE))
        assertTrue(s.disabledKinds.value.isEmpty())

        // An explicit "show everything" is NOT re-defaulted on reload — the
        // sentinel only applies when the key was never written.
        assertTrue(CardSettings(store).isEnabled(CardSettings.TASK_CREATE))
    }
}
