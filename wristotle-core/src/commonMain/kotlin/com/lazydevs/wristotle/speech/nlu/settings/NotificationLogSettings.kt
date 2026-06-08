// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
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
 * R4 batch 10 — lifted from :app onto the [KeyValueStore] seam.
 */
class NotificationLogSettings(private val store: KeyValueStore) {

    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        store.putBoolean(KEY_ENABLED, value)
        _enabled.value = value
    }

    companion object {
        const val PREFS_NAME = "wristotle_notif_log_settings"
        private const val KEY_ENABLED = "enabled"

        const val DEFAULT_ENABLED = false
    }
}
