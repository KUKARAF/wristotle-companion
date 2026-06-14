// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs
package com.lazydevs.wristotle.sport

import com.lazydevs.sportskapi.SportConfigStore
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore

/**
 * Persists `sportskapi`'s cached remote config (one opaque JSON slot) via the
 * app's [KeyValueStore]. Falls back to the library's bundled default when empty.
 */
class SportConfigStoreImpl(private val store: KeyValueStore) : SportConfigStore {
    override fun read(): String? = store.getString(KEY, "").ifEmpty { null }
    override fun write(value: String) = store.putString(KEY, value)

    companion object {
        const val PREFS_NAME = "sports_config_cache"
        private const val KEY = "config"
    }
}
