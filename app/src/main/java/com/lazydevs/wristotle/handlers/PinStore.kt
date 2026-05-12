package com.lazydevs.wristotle.handlers

import android.content.Context

private const val PREFS_NAME = "reminder_pins"
private const val KEY_IDS = "pin_ids"
private const val MAX_PINS = 10
private const val SEPARATOR = ","

class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(pinId: String) {
        val ids = load().toMutableList()
        ids.add(0, pinId)
        if (ids.size > MAX_PINS) ids.subList(MAX_PINS, ids.size).clear()
        prefs.edit().putString(KEY_IDS, ids.joinToString(SEPARATOR)).apply()
    }

    fun remove(pinId: String) {
        val ids = load().toMutableList()
        ids.remove(pinId)
        prefs.edit().putString(KEY_IDS, ids.joinToString(SEPARATOR)).apply()
    }

    fun latest(): String? = load().firstOrNull()

    private fun load(): List<String> {
        val raw = prefs.getString(KEY_IDS, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(SEPARATOR)
    }
}
