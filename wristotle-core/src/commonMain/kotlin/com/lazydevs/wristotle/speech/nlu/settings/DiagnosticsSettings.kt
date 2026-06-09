// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the diagnostics-export feature.
 *
 *   - [redactPii]    — when true (default), the bundle scrubs contact
 *                      names, phone digit runs, and message bodies
 *                      from conversation rows and log lines before
 *                      the user pastes them anywhere public.
 *   - [includeAudio] — when true (default false), the last 3 saved
 *                      dictation WAVs are copied to a stable temp
 *                      location and their paths are surfaced in the
 *                      report so the user can attach them manually.
 *
 * R4 batch 10 — lifted from :app onto the [KeyValueStore] seam.
 */
class DiagnosticsSettings(private val store: KeyValueStore) {

    private val _redactPii = MutableStateFlow(store.getBoolean(KEY_REDACT, true))
    val redactPii: StateFlow<Boolean> = _redactPii.asStateFlow()

    private val _includeAudio = MutableStateFlow(store.getBoolean(KEY_INCLUDE_AUDIO, false))
    val includeAudio: StateFlow<Boolean> = _includeAudio.asStateFlow()

    fun setRedactPii(enabled: Boolean) {
        store.putBoolean(KEY_REDACT, enabled)
        _redactPii.value = enabled
    }

    fun setIncludeAudio(enabled: Boolean) {
        store.putBoolean(KEY_INCLUDE_AUDIO, enabled)
        _includeAudio.value = enabled
    }

    companion object {
        const val PREFS_NAME = "wristotle_diagnostics"
        private const val KEY_REDACT = "redact_pii"
        private const val KEY_INCLUDE_AUDIO = "include_audio"
    }
}
