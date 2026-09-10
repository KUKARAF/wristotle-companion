// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where "set a timer for N" runs. Default (false) hands off to the phone's
 * system Clock app as before. When [timerOnWatch] is on, the companion sends
 * the duration to the watch, which runs a native countdown via the Pebble
 * wakeup API and buzzes when it's up — no phone needed. Companion-local: it
 * only decides routing; the watch just reacts to the timer-start message.
 */
class TimerSettings(private val store: KeyValueStore) {

    private val _timerOnWatch = MutableStateFlow(store.getBoolean(KEY_ON_WATCH, false))
    val timerOnWatch: StateFlow<Boolean> = _timerOnWatch

    fun setTimerOnWatch(value: Boolean) {
        if (_timerOnWatch.value == value) return
        store.putBoolean(KEY_ON_WATCH, value)
        _timerOnWatch.value = value
    }

    companion object {
        const val PREFS_NAME = "timer_settings"
        private const val KEY_ON_WATCH = "timer_on_watch"
    }
}
