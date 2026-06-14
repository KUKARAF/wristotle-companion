// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gating tests for [TtsProviderSettings.shouldSpeak]. This is the single
 * decision the dispatch hook in `PebbleListenerService` makes per voice
 * query — a bug here silences every reply or speaks every reply. Cheap
 * to test, high regression cost if it ever drifted.
 */
class TtsProviderSettingsTest {

    private fun fresh() = TtsProviderSettings(InMemoryKeyValueStore())

    @Test fun `defaults silently — master off, only sports pre-enabled`() {
        val s = fresh()
        assertFalse(s.enabled.value, "fresh install: master toggle off")
        // Sports ships pre-ticked in the picker; everything else is opt-in.
        assertEquals(TtsProviderSettings.DEFAULT_INTENTS, s.intentsEnabled.value)
        assertTrue(TtsProviderSettings.INTENT_SPORT in s.intentsEnabled.value)
        // ...but the master toggle being off means nothing speaks yet.
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_SPORT))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
    }

    @Test fun `deselect-all persists as empty, not reverting to the default`() {
        // The fresh-install default (Sports) only applies when the key was
        // never written. Once the user clears the picker, that empty choice
        // must survive a reload — not silently re-enable Sports.
        val store = InMemoryKeyValueStore()
        TtsProviderSettings(store).setIntentsEnabled(emptySet())
        val recreated = TtsProviderSettings(store)
        assertTrue(recreated.intentsEnabled.value.isEmpty())
        assertFalse(recreated.shouldSpeak(TtsProviderSettings.INTENT_SPORT))
    }

    @Test fun `master off blocks even when intent is enabled`() {
        val s = fresh()
        s.setIntentEnabled(TtsProviderSettings.INTENT_ASK_AGENT, true)
        assertFalse(s.enabled.value)
        // Per-intent on but master off → silent. This is the bug class
        // a regression here would create.
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
    }

    @Test fun `master on but intent unticked → silent`() {
        val s = fresh()
        s.setEnabled(true)
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_WEATHER))
    }

    @Test fun `both on for one intent does not bleed into others`() {
        val s = fresh()
        s.setEnabled(true)
        s.setIntentEnabled(TtsProviderSettings.INTENT_ASK_AGENT, true)
        assertTrue(s.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_WEATHER))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_REMINDER))
    }

    @Test fun `unknown intent name never speaks`() {
        // An intent that's not in our INTENT_* set (e.g. a future intent
        // added to the Intent enum but not yet to the picker). Should
        // default to NOT speaking — opt-in by name only.
        val s = fresh()
        s.setEnabled(true)
        assertFalse(s.shouldSpeak("MysteryFutureIntent"))
    }

    @Test fun `setIntentsEnabled bulk replaces the whole set`() {
        val s = fresh()
        s.setEnabled(true)
        s.setIntentEnabled(TtsProviderSettings.INTENT_REMINDER, true)
        s.setIntentEnabled(TtsProviderSettings.INTENT_ASK_AGENT, true)
        assertTrue(s.shouldSpeak(TtsProviderSettings.INTENT_REMINDER))

        // Bulk-replace to just one intent — others should drop off.
        s.setIntentsEnabled(setOf(TtsProviderSettings.INTENT_WEATHER))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_REMINDER))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
        assertTrue(s.shouldSpeak(TtsProviderSettings.INTENT_WEATHER))
    }

    @Test fun `setIntentsEnabled with empty set silences everything`() {
        // "Deselect all" path — master can still be on, but nothing speaks.
        val s = fresh()
        s.setEnabled(true)
        s.setIntentsEnabled(setOf(
            TtsProviderSettings.INTENT_ASK_AGENT,
            TtsProviderSettings.INTENT_WEATHER,
        ))
        s.setIntentsEnabled(emptySet())
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
        assertFalse(s.shouldSpeak(TtsProviderSettings.INTENT_WEATHER))
        assertEquals(emptySet(), s.intentsEnabled.value)
    }

    @Test fun `settings survive recreate via the same KeyValueStore`() {
        // Simulates restoring a backup or a process restart: a fresh
        // settings instance reading the same underlying store sees the
        // prior writes.
        val store = InMemoryKeyValueStore()
        TtsProviderSettings(store).apply {
            setEnabled(true)
            setIntentsEnabled(setOf(
                TtsProviderSettings.INTENT_ASK_AGENT,
                TtsProviderSettings.INTENT_MORNING_BRIEF,
            ))
        }
        val recreated = TtsProviderSettings(store)
        assertTrue(recreated.enabled.value)
        assertTrue(recreated.shouldSpeak(TtsProviderSettings.INTENT_ASK_AGENT))
        assertTrue(recreated.shouldSpeak(TtsProviderSettings.INTENT_MORNING_BRIEF))
        assertFalse(recreated.shouldSpeak(TtsProviderSettings.INTENT_WEATHER))
    }
}
