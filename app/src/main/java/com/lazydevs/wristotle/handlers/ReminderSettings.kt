package com.lazydevs.wristotle.handlers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the reminder feature. Currently a single knob —
 * how far in the future to schedule a reminder when the user dictates one
 * without a time ("remind me to buy milk"). The companion's [ReminderSlots]
 * reads this at extract time so the confirm prompt and the actual pin both
 * see the defaulted timestamp.
 *
 * Whitelisted minute values: 5, 10, 15, 30, 45, 60, 90. An unexpected
 * value (manually edited prefs, future migration) falls back to
 * [DEFAULT_OFFSET_MIN].
 */
class ReminderSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _defaultOffsetMin = MutableStateFlow(readOffset())
    val defaultOffsetMin: StateFlow<Int> = _defaultOffsetMin.asStateFlow()

    fun setDefaultOffsetMin(minutes: Int) {
        val sanitized = if (minutes in ALLOWED_OFFSET_MIN) minutes else DEFAULT_OFFSET_MIN
        prefs.edit { putInt(KEY_DEFAULT_OFFSET_MIN, sanitized) }
        _defaultOffsetMin.value = sanitized
    }

    private fun readOffset(): Int {
        val raw = prefs.getInt(KEY_DEFAULT_OFFSET_MIN, DEFAULT_OFFSET_MIN)
        return if (raw in ALLOWED_OFFSET_MIN) raw else DEFAULT_OFFSET_MIN
    }

    companion object {
        private const val PREFS_NAME = "wristotle_reminder_settings"
        private const val KEY_DEFAULT_OFFSET_MIN = "default_offset_min"

        const val DEFAULT_OFFSET_MIN = 30
        val ALLOWED_OFFSET_MIN = listOf(5, 10, 15, 30, 45, 60, 90)

        /** Persistent-reminder cadence in minutes — used by phase B's
         *  scheduler to re-arm the phone notification after a reminder
         *  has fired. Phase C exposes these via the Settings card; for
         *  now the handler reads the default directly. */
        const val DEFAULT_INTERVAL_MIN = 10
        val ALLOWED_INTERVAL_MIN = listOf(5, 10, 15, 30)

        /** Max times the phone notification can re-fire before the
         *  scheduler gives up — saves users from runaway nagging if
         *  they put the phone down without dismissing. */
        const val DEFAULT_MAX_ATTEMPTS = 5
        val ALLOWED_MAX_ATTEMPTS = listOf(3, 5, 7, 10)
    }
}
