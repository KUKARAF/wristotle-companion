// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore

/**
 * Trivial in-memory [KeyValueStore] for commonTest. Reads return the
 * default when a key was never written, just like
 * `SharedPreferences.getX(key, default)`. No type checking on read —
 * if a caller wrote an Int then reads a String, the cast on the
 * read side will throw, exactly like SharedPreferences. Tests should
 * be careful to read what they wrote.
 */
internal class InMemoryKeyValueStore : KeyValueStore {
    private val data = HashMap<String, Any>()

    override fun getString(key: String, default: String): String =
        data[key] as? String ?: default
    override fun getInt(key: String, default: Int): Int =
        data[key] as? Int ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean =
        data[key] as? Boolean ?: default
    override fun putString(key: String, value: String) { data[key] = value }
    override fun putInt(key: String, value: Int) { data[key] = value }
    override fun putBoolean(key: String, value: Boolean) { data[key] = value }
}
