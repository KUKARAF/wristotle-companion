// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the persisted notification log.
 *
 * Default OFF. The log captures notification *metadata* only — package
 * id + a conversation key derived from shortcut/channel/tag/id + the
 * post timestamp. Body / title / extras are never read. Even so, the
 * project's privacy-default-off stance means the toggle ships disabled
 * and the user has to explicitly opt in before any rows land.
 *
 * Lives at its own SharedPrefs file so a "Clear log" + a settings reset
 * stay decoupled — Phase C's UI calls
 * [com.lazydevs.wristotle.notifications.NotificationLogStore.deleteAll]
 * for the data half independently of touching this prefs file.
 */
class NotificationLogSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    companion object {
        private const val PREFS_NAME = "wristotle_notif_log_settings"
        private const val KEY_ENABLED = "enabled"

        const val DEFAULT_ENABLED = false
    }
}
