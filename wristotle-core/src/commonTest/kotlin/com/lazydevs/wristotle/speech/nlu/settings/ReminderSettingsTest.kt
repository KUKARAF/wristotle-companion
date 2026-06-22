// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each reminder knob (offset / interval / max-attempts) sanitizes against an
 * allow-list on BOTH read and write, falling back to its DEFAULT for an
 * out-of-list value (manually-edited prefs / future migration). The
 * persistent-reminder scheduler reads these fresh per re-arm, so a bad value
 * silently mis-pacing the nag loop is the failure this guards.
 */
class ReminderSettingsTest {

    @Test fun freshDefaults() {
        val s = ReminderSettings(InMemoryKeyValueStore())
        assertEquals(ReminderSettings.DEFAULT_OFFSET_MIN, s.defaultOffsetMin.value)
        assertEquals(ReminderSettings.DEFAULT_INTERVAL_MIN, s.defaultIntervalMin.value)
        assertEquals(ReminderSettings.DEFAULT_MAX_ATTEMPTS, s.defaultMaxAttempts.value)
    }

    @Test fun validValuesPersistAcrossReload() {
        val store = InMemoryKeyValueStore()
        ReminderSettings(store).apply {
            setDefaultOffsetMin(45)
            setDefaultIntervalMin(15)
            setDefaultMaxAttempts(7)
        }
        val reloaded = ReminderSettings(store)
        assertEquals(45, reloaded.defaultOffsetMin.value)
        assertEquals(15, reloaded.defaultIntervalMin.value)
        assertEquals(7, reloaded.defaultMaxAttempts.value)
    }

    @Test fun outOfListWriteFallsBackToDefault() {
        val s = ReminderSettings(InMemoryKeyValueStore())
        s.setDefaultOffsetMin(7)          // not in {5,10,15,30,45,60,90}
        s.setDefaultIntervalMin(20)       // not in {5,10,15,30}
        s.setDefaultMaxAttempts(4)        // not in {3,5,7,10}
        assertEquals(ReminderSettings.DEFAULT_OFFSET_MIN, s.defaultOffsetMin.value)
        assertEquals(ReminderSettings.DEFAULT_INTERVAL_MIN, s.defaultIntervalMin.value)
        assertEquals(ReminderSettings.DEFAULT_MAX_ATTEMPTS, s.defaultMaxAttempts.value)
    }

    @Test fun outOfListStoredValueIsSanitizedOnRead() {
        // Simulate a hand-edited / migrated prefs value the allow-list rejects.
        val store = InMemoryKeyValueStore().apply {
            putInt("default_offset_min", 999)
            putInt("default_interval_min", 0)
            putInt("default_max_attempts", 100)
        }
        val s = ReminderSettings(store)
        assertEquals(ReminderSettings.DEFAULT_OFFSET_MIN, s.defaultOffsetMin.value)
        assertEquals(ReminderSettings.DEFAULT_INTERVAL_MIN, s.defaultIntervalMin.value)
        assertEquals(ReminderSettings.DEFAULT_MAX_ATTEMPTS, s.defaultMaxAttempts.value)
    }
}
