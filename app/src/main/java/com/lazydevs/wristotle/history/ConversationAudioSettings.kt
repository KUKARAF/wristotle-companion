package com.lazydevs.wristotle.history

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preference for the conversation audio capture feature.
 *
 * Defaults to **off** — raw audio is materially more sensitive than the
 * transcripts we already keep, so we make the user opt in explicitly.
 *
 * When enabled, [com.lazydevs.wristotle.speech.whisper.WhisperRecognizer]
 * writes the PCM buffer for each dictation as a `.wav` into the
 * conversation-audio directory; [ConversationAudioStore] caps that
 * directory at [MAX_FILES] entries.
 */
class ConversationAudioSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _captureEnabled = MutableStateFlow(prefs.getBoolean(KEY_CAPTURE, false))
    val captureEnabled: StateFlow<Boolean> = _captureEnabled.asStateFlow()

    fun setCaptureEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CAPTURE, enabled) }
        _captureEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "wristotle_conversation_audio"
        private const val KEY_CAPTURE = "capture_enabled"

        /** Hard cap on how many .wav files we retain. Older files are FIFO-evicted. */
        const val MAX_FILES = 5
    }
}
