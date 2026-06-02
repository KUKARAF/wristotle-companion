package com.lazydevs.wristotle.alarms

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-feature SharedPrefs holder for the Alarms surface.
 *
 * Only field today is [defaultDestination] — used by
 * [com.lazydevs.wristotle.handlers.SetAlarmHandler] when a voice
 * "set an alarm for 7am" lands without an explicit destination. The
 * companion UI's AlarmEditorDialog has its own radio group that
 * defaults to whatever this is set to, so changing the default flows
 * through to both surfaces.
 */
class AlarmSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _defaultDestination = MutableStateFlow(readDestination())
    val defaultDestination: StateFlow<AlarmDestination> = _defaultDestination.asStateFlow()

    fun setDefaultDestination(destination: AlarmDestination) {
        prefs.edit().putString(KEY_DEFAULT_DESTINATION, destination.name).apply()
        _defaultDestination.value = destination
    }

    private fun readDestination(): AlarmDestination {
        val raw = prefs.getString(KEY_DEFAULT_DESTINATION, null) ?: return AlarmDestination.Phone
        return runCatching { AlarmDestination.valueOf(raw) }.getOrElse { AlarmDestination.Phone }
    }

    companion object {
        private const val PREFS_NAME = "wristotle_alarms"
        private const val KEY_DEFAULT_DESTINATION = "default_destination"
    }
}
