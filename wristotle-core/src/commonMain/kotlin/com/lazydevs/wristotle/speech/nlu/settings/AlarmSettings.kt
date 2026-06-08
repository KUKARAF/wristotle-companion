// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.alarms.AlarmDestination
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import com.lazydevs.wristotle.speech.nlu.store.getEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-feature settings holder for the Alarms surface.
 *
 * Only field today is [defaultDestination] — used by the SetAlarmHandler
 * when a voice "set an alarm for 7am" lands without an explicit
 * destination. The companion UI's AlarmEditorDialog has its own radio
 * group that defaults to whatever this is set to, so changing the
 * default flows through to both surfaces.
 *
 * R4 batch 4 — lifted from :app onto the [KeyValueStore] seam.
 */
class AlarmSettings(private val store: KeyValueStore) {

    private val _defaultDestination = MutableStateFlow(readDestination())
    val defaultDestination: StateFlow<AlarmDestination> = _defaultDestination.asStateFlow()

    fun setDefaultDestination(destination: AlarmDestination) {
        store.putString(KEY_DEFAULT_DESTINATION, destination.name)
        _defaultDestination.value = destination
    }

    private fun readDestination(): AlarmDestination =
        store.getEnum(KEY_DEFAULT_DESTINATION, AlarmDestination.Phone)

    companion object {
        const val PREFS_NAME = "wristotle_alarms"
        private const val KEY_DEFAULT_DESTINATION = "default_destination"
    }
}
