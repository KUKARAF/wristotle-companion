// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What happens to the dictation's audio when the user appends to an existing note.
 *
 *   [MERGE] — concatenate the new PCM into the prior note's `.wav` so the
 *     note still has one playable file (the original behavior).
 *   [SEPARATE] — keep each appended dictation as its own `.wav`; the note
 *     plays them back-to-back when the user taps play.
 */
enum class AppendAudioMode { MERGE, SEPARATE }

/**
 * Read surface of [NoteSettings] used by non-UI callers (the repository).
 * Lets the repo depend on the StateFlow contract instead of the concrete
 * Context-bound [NoteSettings], so tests can inject a fake without
 * standing up Android prefs.
 */
interface NoteSettingsView {
    val keepLast: StateFlow<Int>
    val appendAudioMode: StateFlow<AppendAudioMode>
}

/**
 * User preference for note retention. Stored count is the cap; the
 * special value [UNLIMITED] = 0 means "keep all notes" (default).
 *
 * Mirrors the simple shape of [com.lazydevs.wristotle.history.ConversationSettings]
 * but is count-based, not time-based — long-form notes don't age the
 * way ephemeral conversation entries do.
 */
class NoteSettings(context: Context) : NoteSettingsView {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _keepLast = MutableStateFlow(prefs.getInt(KEY_KEEP_LAST, UNLIMITED))
    /** Current cap; [UNLIMITED] (=0) means no cap. */
    override val keepLast: StateFlow<Int> = _keepLast.asStateFlow()

    fun setKeepLast(value: Int) {
        val v = if (value < 0) 0 else value
        prefs.edit { putInt(KEY_KEEP_LAST, v) }
        _keepLast.value = v
    }

    private val _appendAudioMode = MutableStateFlow(loadAppendAudioMode())
    override val appendAudioMode: StateFlow<AppendAudioMode> = _appendAudioMode.asStateFlow()

    fun setAppendAudioMode(value: AppendAudioMode) {
        prefs.edit { putString(KEY_APPEND_AUDIO_MODE, value.name) }
        _appendAudioMode.value = value
    }

    private fun loadAppendAudioMode(): AppendAudioMode = runCatching {
        AppendAudioMode.valueOf(prefs.getString(KEY_APPEND_AUDIO_MODE, AppendAudioMode.MERGE.name)!!)
    }.getOrDefault(AppendAudioMode.MERGE)

    companion object {
        private const val PREFS_NAME = "wristotle_notes"
        private const val KEY_KEEP_LAST = "keep_last"
        private const val KEY_APPEND_AUDIO_MODE = "append_audio_mode"

        /** Special value: no cap, keep every note. */
        const val UNLIMITED = 0

        /** Presets surfaced in the Settings card. */
        val ALLOWED_KEEP_LAST: List<Int> = listOf(UNLIMITED, 50, 100, 200, 500)
    }
}