// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.storage

import android.content.Context
import androidx.core.content.edit
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore

/**
 * Android adapter that fulfils the multiplatform [KeyValueStore] contract
 * by forwarding every read / write to a [android.content.SharedPreferences]
 * file named [prefsName].
 *
 * Same per-prefs-file granularity as the SharedPreferences API itself —
 * each settings class gets its own named file (matching what the
 * old `getSharedPreferences("wristotle_nlu_settings", …)` calls used).
 *
 */
class SharedPreferencesKeyValueStore(
    context: Context,
    prefsName: String,
) : KeyValueStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        prefsName,
        Context.MODE_PRIVATE,
    )

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }
}

/**
 * Convenience constructor used at every `WristotleApplication` settings
 * wiring site. Collapses the four-line
 * `SharedPreferencesKeyValueStore(this, X.PREFS_NAME)` to a one-liner.
 *
 * Pure mechanics — the production code path through the resulting
 * adapter is identical.
 */
fun Context.kvStore(prefsName: String): KeyValueStore =
    SharedPreferencesKeyValueStore(this, prefsName)
