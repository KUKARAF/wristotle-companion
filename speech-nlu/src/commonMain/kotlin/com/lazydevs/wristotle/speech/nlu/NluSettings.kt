// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for the NLU intent layer. Currently a single toggle —
 * "Learn from my voice commands" — which gates `LearningCollector`
 * inserts. Routing itself is gated on the active NLU model being present;
 * no toggle for that, the user controls it via the Settings model card.
 *
 * R3 batch 4 — lifted from :app. The `Context`-based SharedPreferences
 * dependency replaced with a [KeyValueStore] constructor param;
 * production wires it to a `SharedPreferencesKeyValueStore` over the
 * existing "wristotle_nlu_settings" file so the persisted value
 * survives the upgrade.
 */
class NluSettings(store: KeyValueStore) {

    private val storeRef = store

    private val _learningEnabled = MutableStateFlow(storeRef.getBoolean(KEY_LEARNING, true))
    val learningEnabled: StateFlow<Boolean> = _learningEnabled.asStateFlow()

    fun setLearningEnabled(enabled: Boolean) {
        storeRef.putBoolean(KEY_LEARNING, enabled)
        _learningEnabled.value = enabled
    }

    companion object {
        /** SharedPreferences file name the Android-side store uses.
         *  Kept as the file convention rather than the StoredApp /
         *  AppIndex prefs naming so the v1.x upgrade path doesn't lose
         *  the persisted "learning enabled" toggle. */
        const val PREFS_NAME = "wristotle_nlu_settings"
        private const val KEY_LEARNING = "learning_enabled"

        /**
         * Minimum cosine similarity for an NLU prediction to be routed to a
         * handler. Below this we treat the query as Intent.Unknown — sub-
         * threshold predictions still get logged for tuning.
         */
        const val ROUTE_THRESHOLD = 0.55f

        /**
         * Required gap between top-1 and top-2 confidences. Catches the
         * "this is one of two intents and I have no idea which" cases —
         * routes them to Unknown rather than guessing.
         */
        const val ROUTE_MARGIN = 0.10f
    }
}
