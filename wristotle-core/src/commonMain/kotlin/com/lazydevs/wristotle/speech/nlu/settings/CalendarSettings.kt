// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which calendar voice-created events are written to. [targetCalendarId] of
 * [AUTOMATIC] (0) means "let the app pick" (primary, else first writable) —
 * the pre-existing behaviour. A positive id pins a specific calendar the user
 * chose in Settings.
 *
 * NOT backed up: calendar ids are device-specific (the same account gets
 * different ids on a new device), so restoring one would point at the wrong
 * calendar. The picker just re-selects on the new device.
 *
 * Stored as a string because [KeyValueStore] has no long accessor and a
 * calendar `_ID` is a Long.
 */
class CalendarSettings(private val store: KeyValueStore) {

    private val _targetCalendarId = MutableStateFlow(
        store.getString(KEY_TARGET, "").toLongOrNull() ?: AUTOMATIC,
    )
    val targetCalendarId: StateFlow<Long> = _targetCalendarId

    fun setTargetCalendarId(id: Long) {
        val normalised = if (id > 0L) id else AUTOMATIC
        if (_targetCalendarId.value == normalised) return
        store.putString(KEY_TARGET, if (normalised == AUTOMATIC) "" else normalised.toString())
        _targetCalendarId.value = normalised
    }

    companion object {
        const val PREFS_NAME = "calendar_settings"
        private const val KEY_TARGET = "target_calendar_id"
        /** Sentinel: let the app auto-pick the calendar. */
        const val AUTOMATIC = 0L
    }
}
