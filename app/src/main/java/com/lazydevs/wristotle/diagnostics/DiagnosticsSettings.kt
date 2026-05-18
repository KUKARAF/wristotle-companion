package com.lazydevs.wristotle.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the diagnostics-export feature.
 *
 *   - [redactPii]    — when true (default), the bundle scrubs
 *                      contact names, phone digit runs, and message
 *                      bodies from conversation rows and log lines
 *                      before the user pastes them anywhere public.
 *   - [includeAudio] — when true (default false), the last 3 saved
 *                      dictation WAVs are copied to a stable temp
 *                      location and their paths are surfaced in the
 *                      report so the user can attach them manually.
 *                      Has no effect when conversation audio capture
 *                      itself is off — there's nothing to attach.
 */
class DiagnosticsSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _redactPii = MutableStateFlow(prefs.getBoolean(KEY_REDACT, true))
    val redactPii: StateFlow<Boolean> = _redactPii.asStateFlow()

    private val _includeAudio = MutableStateFlow(prefs.getBoolean(KEY_INCLUDE_AUDIO, false))
    val includeAudio: StateFlow<Boolean> = _includeAudio.asStateFlow()

    fun setRedactPii(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REDACT, enabled) }
        _redactPii.value = enabled
    }

    fun setIncludeAudio(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INCLUDE_AUDIO, enabled) }
        _includeAudio.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "wristotle_diagnostics"
        const val KEY_REDACT = "redact_pii"
        const val KEY_INCLUDE_AUDIO = "include_audio"
    }
}
