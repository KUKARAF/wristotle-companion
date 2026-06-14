// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.sportskapi.SportSubject
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Companion-local settings for the Sports feature (not watch-mirrored —
 * sports is fully companion-resolved like Weather):
 *
 *  - **favorites** — saved teams/leagues so "did we win" / "when's the next
 *    game" work with no team named. Stored as a JSON list of the library's
 *    provider-neutral [SportSubject].
 *  - **preferredSport** — biases team-name disambiguation (e.g. "basketball"
 *    so "city" leans NBA-adjacent). Empty = no preference.
 *
 * Backed by [KeyValueStore]; exposes [StateFlow]s for the Settings card.
 */
class SportSettings(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val favSerializer = ListSerializer(SportSubject.serializer())

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<SportSubject>> = _favorites

    private val _preferredSport = MutableStateFlow(store.getString(KEY_PREF_SPORT, "").trim())
    val preferredSport: StateFlow<String> = _preferredSport

    /** Add (or move-to-front) a favorite, deduped by provider id. */
    fun addFavorite(subject: SportSubject) {
        val next = (listOf(subject) + _favorites.value.filterNot { it.id == subject.id }).take(MAX_FAVORITES)
        persist(next)
    }

    fun removeFavorite(id: String) {
        persist(_favorites.value.filterNot { it.id == id })
    }

    fun setPreferredSport(value: String) {
        val v = value.trim()
        if (_preferredSport.value == v) return
        store.putString(KEY_PREF_SPORT, v)
        _preferredSport.value = v
    }

    private fun persist(list: List<SportSubject>) {
        store.putString(KEY_FAVORITES, json.encodeToString(favSerializer, list))
        _favorites.value = list
    }

    private fun readFavorites(): List<SportSubject> = runCatching {
        val raw = store.getString(KEY_FAVORITES, "")
        if (raw.isEmpty()) emptyList() else json.decodeFromString(favSerializer, raw)
    }.getOrElse { emptyList() }

    companion object {
        const val PREFS_NAME = "sport_settings"
        const val MAX_FAVORITES = 10
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PREF_SPORT = "preferred_sport"
    }
}
