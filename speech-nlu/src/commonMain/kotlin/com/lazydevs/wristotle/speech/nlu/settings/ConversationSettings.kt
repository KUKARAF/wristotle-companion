// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the conversation history. Currently just the
 * retention window in days; built on [KeyValueStore] (no Room overhead
 * for a single int) and exposed as a [StateFlow] so the Settings screen
 * can render the current value reactively.
 *
 * Whitelisted values: 1, 10, 20, 30. The UI only offers those choices —
 * an unexpected value (from a manually edited prefs file, or a future
 * migration) falls back to [DEFAULT_RETENTION_DAYS].
 *
 * R3 batch 5 — lifted from :app. Production wires it to a
 * `SharedPreferencesKeyValueStore` over [PREFS_NAME] so the persisted
 * value survives the upgrade.
 */
class ConversationSettings(private val store: KeyValueStore) {

    private val _retentionDays = MutableStateFlow(readRetention())
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    fun setRetentionDays(days: Int) {
        val sanitized = if (days in ALLOWED_RETENTION_DAYS) days else DEFAULT_RETENTION_DAYS
        store.putInt(KEY_RETENTION_DAYS, sanitized)
        _retentionDays.value = sanitized
    }

    private fun readRetention(): Int {
        val raw = store.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        return if (raw in ALLOWED_RETENTION_DAYS) raw else DEFAULT_RETENTION_DAYS
    }

    companion object {
        /** SharedPreferences file name the Android-side store uses. */
        const val PREFS_NAME = "wristotle_conversation_settings"
        private const val KEY_RETENTION_DAYS = "retention_days"

        const val DEFAULT_RETENTION_DAYS = 10
        val ALLOWED_RETENTION_DAYS = listOf(1, 10, 20, 30)
    }
}
