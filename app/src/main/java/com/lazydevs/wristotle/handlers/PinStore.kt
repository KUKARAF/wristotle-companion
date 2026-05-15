package com.lazydevs.wristotle.handlers

import android.content.Context

/**
 * SharedPreferences-backed ring buffer of recent reminder pin IDs.
 *
 * All mutating operations are guarded by an intrinsic lock so concurrent
 * reminders dispatched from different coroutines can't race on the underlying
 * read-modify-write of the prefs string. [latest] is also locked so callers see
 * a value consistent with the most recent write.
 */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun save(pinId: String) = synchronized(lock) {
        val ids = load().toMutableList()
        ids.add(0, pinId)
        if (ids.size > MAX_PINS) ids.subList(MAX_PINS, ids.size).clear()
        prefs.edit().putString(KEY_IDS, ids.joinToString(SEPARATOR)).apply()
    }

    fun remove(pinId: String) = synchronized(lock) {
        val ids = load().toMutableList()
        ids.remove(pinId)
        prefs.edit().putString(KEY_IDS, ids.joinToString(SEPARATOR)).apply()
    }

    fun latest(): String? = synchronized(lock) { load().firstOrNull() }

    private fun load(): List<String> {
        val raw = prefs.getString(KEY_IDS, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(SEPARATOR)
    }

    private companion object {
        const val PREFS_NAME = "reminder_pins"
        const val KEY_IDS = "pin_ids"
        const val MAX_PINS = 10
        const val SEPARATOR = ","
    }
}
