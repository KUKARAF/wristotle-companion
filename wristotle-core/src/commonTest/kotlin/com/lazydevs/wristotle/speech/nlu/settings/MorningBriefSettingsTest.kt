// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.briefing.BriefSection
import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorningBriefSettingsTest {

    @Test
    fun `fresh install includes every section`() {
        val s = MorningBriefSettings(InMemoryKeyValueStore())
        BriefSection.entries.forEach {
            assertTrue(s.isEnabled(it), "${it.key} should be on by default")
        }
        assertTrue(s.disabledSections.value.isEmpty())
    }

    @Test
    fun `disabling a section persists across reloads`() {
        val store = InMemoryKeyValueStore()
        MorningBriefSettings(store).setEnabled(BriefSection.ALARMS, false)
        val reloaded = MorningBriefSettings(store)
        assertFalse(reloaded.isEnabled(BriefSection.ALARMS))
        assertTrue(reloaded.isEnabled(BriefSection.MEETINGS))
    }

    @Test
    fun `re-enabling a section clears it from the disabled set`() {
        val s = MorningBriefSettings(InMemoryKeyValueStore())
        s.setEnabled(BriefSection.NOTES, false)
        assertFalse(s.isEnabled(BriefSection.NOTES))
        s.setEnabled(BriefSection.NOTES, true)
        assertTrue(s.isEnabled(BriefSection.NOTES))
        assertTrue(s.disabledSections.value.isEmpty())
    }

    @Test
    fun `setEnabledSections replaces the whole selection`() {
        val s = MorningBriefSettings(InMemoryKeyValueStore())
        s.setEnabledSections(setOf(BriefSection.MEETINGS, BriefSection.TASKS))
        assertTrue(s.isEnabled(BriefSection.MEETINGS))
        assertTrue(s.isEnabled(BriefSection.TASKS))
        assertFalse(s.isEnabled(BriefSection.ALARMS))
        assertFalse(s.isEnabled(BriefSection.NOTES))
    }

    @Test
    fun `snapshot and restore round-trips for backup`() {
        val s = MorningBriefSettings(InMemoryKeyValueStore())
        s.setEnabled(BriefSection.MESSAGES, false)
        s.setEnabled(BriefSection.REMINDERS, false)
        val snap = s.snapshotDisabled()

        val restored = MorningBriefSettings(InMemoryKeyValueStore())
        restored.restoreDisabled(snap)
        assertFalse(restored.isEnabled(BriefSection.MESSAGES))
        assertFalse(restored.isEnabled(BriefSection.REMINDERS))
        assertTrue(restored.isEnabled(BriefSection.TASKS))
    }

    @Test
    fun `an unknown disabled key never hides a real section`() {
        val s = MorningBriefSettings(InMemoryKeyValueStore())
        s.restoreDisabled(setOf("some_future_section"))
        BriefSection.entries.forEach { assertTrue(s.isEnabled(it)) }
    }

    @Test
    fun `fromKey resolves known keys and rejects unknown`() {
        assertEquals(BriefSection.MESSAGES, BriefSection.fromKey("messages"))
        assertEquals(null, BriefSection.fromKey("nope"))
    }
}
