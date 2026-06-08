// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preference for the conversation audio capture feature.
 *
 * Defaults to **off** — raw audio is materially more sensitive than the
 * transcripts we already keep, so we make the user opt in explicitly.
 *
 * When enabled, the Whisper recognizer writes the PCM buffer for each
 * dictation as a `.wav` into the conversation-audio directory; the
 * companion-side `ConversationAudioStore` caps that directory at
 * [MAX_FILES] entries.
 *
 * R3 batch 5 — lifted from :app onto the [KeyValueStore] seam.
 */
class ConversationAudioSettings(private val store: KeyValueStore) {

    private val _captureEnabled = MutableStateFlow(store.getBoolean(KEY_CAPTURE, false))
    val captureEnabled: StateFlow<Boolean> = _captureEnabled.asStateFlow()

    fun setCaptureEnabled(enabled: Boolean) {
        store.putBoolean(KEY_CAPTURE, enabled)
        _captureEnabled.value = enabled
    }

    companion object {
        /** SharedPreferences file name the Android-side store uses. */
        const val PREFS_NAME = "wristotle_conversation_audio"
        private const val KEY_CAPTURE = "capture_enabled"

        /** Hard cap on how many .wav files we retain. Older files are FIFO-evicted. */
        const val MAX_FILES = 5
    }
}
