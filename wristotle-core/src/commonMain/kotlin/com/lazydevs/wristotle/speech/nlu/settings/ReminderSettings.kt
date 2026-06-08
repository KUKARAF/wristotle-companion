// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the reminder feature.
 *
 *  - [defaultOffsetMin] — how far in the future to schedule a reminder when
 *    the user dictates one without a time ("remind me to buy milk"). The
 *    companion's `ReminderSlots` reads this at extract time so the confirm
 *    prompt and the actual pin both see the defaulted timestamp.
 *  - [defaultIntervalMin] — re-fire cadence for persistent reminders. The
 *    scheduler + receiver read this fresh on every re-arm so a Settings
 *    edit takes effect on the very next nag (no restart needed).
 *  - [defaultMaxAttempts] — initial value for `attemptsRemaining` on a new
 *    persistent reminder, read by `ReminderHandler`. Saves users from
 *    runaway nagging if they put the phone down without dismissing.
 *
 * Each knob has a whitelist of allowed values; an unexpected value
 * (manually edited prefs, future migration) falls back to the matching
 * `DEFAULT_*`.
 *
 * R3 batch 4 — lifted from :app. `Context`-based SharedPreferences swapped
 * for [KeyValueStore]; production wires it to a
 * `SharedPreferencesKeyValueStore` over [PREFS_NAME] so the persisted
 * values survive the upgrade.
 */
class ReminderSettings(private val store: KeyValueStore) {

    private val _defaultOffsetMin = MutableStateFlow(readOffset())
    val defaultOffsetMin: StateFlow<Int> = _defaultOffsetMin.asStateFlow()

    private val _defaultIntervalMin = MutableStateFlow(readInterval())
    val defaultIntervalMin: StateFlow<Int> = _defaultIntervalMin.asStateFlow()

    private val _defaultMaxAttempts = MutableStateFlow(readMaxAttempts())
    val defaultMaxAttempts: StateFlow<Int> = _defaultMaxAttempts.asStateFlow()

    fun setDefaultOffsetMin(minutes: Int) {
        val sanitized = if (minutes in ALLOWED_OFFSET_MIN) minutes else DEFAULT_OFFSET_MIN
        store.putInt(KEY_DEFAULT_OFFSET_MIN, sanitized)
        _defaultOffsetMin.value = sanitized
    }

    fun setDefaultIntervalMin(minutes: Int) {
        val sanitized = if (minutes in ALLOWED_INTERVAL_MIN) minutes else DEFAULT_INTERVAL_MIN
        store.putInt(KEY_DEFAULT_INTERVAL_MIN, sanitized)
        _defaultIntervalMin.value = sanitized
    }

    fun setDefaultMaxAttempts(attempts: Int) {
        val sanitized = if (attempts in ALLOWED_MAX_ATTEMPTS) attempts else DEFAULT_MAX_ATTEMPTS
        store.putInt(KEY_DEFAULT_MAX_ATTEMPTS, sanitized)
        _defaultMaxAttempts.value = sanitized
    }

    private fun readOffset(): Int {
        val raw = store.getInt(KEY_DEFAULT_OFFSET_MIN, DEFAULT_OFFSET_MIN)
        return if (raw in ALLOWED_OFFSET_MIN) raw else DEFAULT_OFFSET_MIN
    }

    private fun readInterval(): Int {
        val raw = store.getInt(KEY_DEFAULT_INTERVAL_MIN, DEFAULT_INTERVAL_MIN)
        return if (raw in ALLOWED_INTERVAL_MIN) raw else DEFAULT_INTERVAL_MIN
    }

    private fun readMaxAttempts(): Int {
        val raw = store.getInt(KEY_DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
        return if (raw in ALLOWED_MAX_ATTEMPTS) raw else DEFAULT_MAX_ATTEMPTS
    }

    companion object {
        /** SharedPreferences file name the Android-side store uses. */
        const val PREFS_NAME = "wristotle_reminder_settings"
        private const val KEY_DEFAULT_OFFSET_MIN = "default_offset_min"
        private const val KEY_DEFAULT_INTERVAL_MIN = "default_interval_min"
        private const val KEY_DEFAULT_MAX_ATTEMPTS = "default_max_attempts"

        const val DEFAULT_OFFSET_MIN = 30
        val ALLOWED_OFFSET_MIN = listOf(5, 10, 15, 30, 45, 60, 90)

        /** Persistent-reminder cadence in minutes — re-fire spacing for
         *  the phone notification after the watch timeline pin fires.
         *  Read fresh by the scheduler + receiver on every re-arm so a
         *  Settings edit takes effect without an app restart. */
        const val DEFAULT_INTERVAL_MIN = 10
        val ALLOWED_INTERVAL_MIN = listOf(5, 10, 15, 30)

        /** Cap on how many times the phone notification can re-fire
         *  before the scheduler gives up. Read by `ReminderHandler` at
         *  reminder-creation time and frozen into the record's
         *  `attemptsRemaining`. */
        const val DEFAULT_MAX_ATTEMPTS = 5
        val ALLOWED_MAX_ATTEMPTS = listOf(3, 5, 7, 10)
    }
}
