package com.lazydevs.wristotle.history

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the conversation history. Currently just the
 * retention window in days; built on `SharedPreferences` (no Room overhead
 * for a single int) and exposed as a [StateFlow] so the Settings screen
 * can render the current value reactively.
 *
 * Whitelisted values: 1, 10, 20, 30. The UI only offers those choices —
 * an unexpected value (from a manually edited prefs file, or a future
 * migration) falls back to [DEFAULT_RETENTION_DAYS].
 */
class ConversationSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _retentionDays = MutableStateFlow(readRetention())
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    fun setRetentionDays(days: Int) {
        val sanitized = if (days in ALLOWED_RETENTION_DAYS) days else DEFAULT_RETENTION_DAYS
        prefs.edit { putInt(KEY_RETENTION_DAYS, sanitized) }
        _retentionDays.value = sanitized
    }

    private fun readRetention(): Int {
        val raw = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        return if (raw in ALLOWED_RETENTION_DAYS) raw else DEFAULT_RETENTION_DAYS
    }

    companion object {
        private const val PREFS_NAME = "wristotle_conversation_settings"
        private const val KEY_RETENTION_DAYS = "retention_days"

        const val DEFAULT_RETENTION_DAYS = 10
        val ALLOWED_RETENTION_DAYS = listOf(1, 10, 20, 30)
    }
}
