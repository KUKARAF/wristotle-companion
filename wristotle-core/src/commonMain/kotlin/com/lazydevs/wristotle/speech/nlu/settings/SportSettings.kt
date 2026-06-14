// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.sportskapi.SportSubject
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Companion-local settings for the Sports feature (not watch-mirrored):
 *
 *  - **favorites** — saved teams/leagues so "did we win" works with no team
 *    named. Stored as a JSON list of the library's neutral [SportSubject].
 *  - **preferredSports** — a PRIORITY-ORDERED list of sport keys. When a
 *    spoken name is shared across leagues (e.g. "city" → Manchester City vs
 *    Oklahoma City Thunder), the sport earlier in this list wins. Passed
 *    straight to the library's resolver.
 *
 * Backed by [KeyValueStore]; exposes [StateFlow]s for the Settings card.
 */
class SportSettings(private val store: KeyValueStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val favSerializer = ListSerializer(SportSubject.serializer())
    private val strListSerializer = ListSerializer(String.serializer())

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<SportSubject>> = _favorites

    private val _preferredSports = MutableStateFlow(readPreferredSports())
    val preferredSports: StateFlow<List<String>> = _preferredSports

    /** Sport keys the user opted out of — excluded sports return no info and
     *  never win an ambiguous name. Empty = everything enabled. */
    private val _excludedSports = MutableStateFlow(readExcludedSports())
    val excludedSports: StateFlow<Set<String>> = _excludedSports

    fun addFavorite(subject: SportSubject) {
        val next = (listOf(subject) + _favorites.value.filterNot { it.id == subject.id }).take(MAX_FAVORITES)
        store.putString(KEY_FAVORITES, json.encodeToString(favSerializer, next))
        _favorites.value = next
    }

    fun removeFavorite(id: String) {
        val next = _favorites.value.filterNot { it.id == id }
        store.putString(KEY_FAVORITES, json.encodeToString(favSerializer, next))
        _favorites.value = next
    }

    /**
     * Union-merge [incoming] favorites into the current set — used by Restore
     * so teams added on the new device aren't clobbered. Existing favorites
     * keep their position; new ones (by [SportSubject.id]) append, capped at
     * [MAX_FAVORITES]. A no-op when nothing new arrives.
     */
    fun mergeFavorites(incoming: List<SportSubject>) {
        if (incoming.isEmpty()) return
        val current = _favorites.value
        val seen = current.mapTo(mutableSetOf()) { it.id }
        val merged = (current + incoming.filter { seen.add(it.id) }).take(MAX_FAVORITES)
        if (merged == current) return
        store.putString(KEY_FAVORITES, json.encodeToString(favSerializer, merged))
        _favorites.value = merged
    }

    /** Replace the full priority order (the Settings card reorders + saves). */
    fun setPreferredSports(order: List<String>) {
        if (order == _preferredSports.value) return
        store.putString(KEY_PREF_SPORTS, json.encodeToString(strListSerializer, order))
        _preferredSports.value = order
    }

    /** Enable/disable a sport. Disabled (excluded) sports return no info. */
    fun setSportEnabled(key: String, enabled: Boolean) {
        val next = if (enabled) _excludedSports.value - key else _excludedSports.value + key
        if (next == _excludedSports.value) return
        store.putString(KEY_EXCLUDED_SPORTS, json.encodeToString(strListSerializer, next.toList()))
        _excludedSports.value = next
    }

    /** Replace the whole excluded set (used by Restore). */
    fun setExcludedSports(excluded: Set<String>) {
        if (excluded == _excludedSports.value) return
        store.putString(KEY_EXCLUDED_SPORTS, json.encodeToString(strListSerializer, excluded.toList()))
        _excludedSports.value = excluded
    }

    private fun readFavorites(): List<SportSubject> = runCatching {
        val raw = store.getString(KEY_FAVORITES, "")
        if (raw.isEmpty()) emptyList() else json.decodeFromString(favSerializer, raw)
    }.getOrElse { emptyList() }

    private fun readPreferredSports(): List<String> = runCatching {
        val raw = store.getString(KEY_PREF_SPORTS, "")
        val stored = if (raw.isEmpty()) emptyList() else json.decodeFromString(strListSerializer, raw)
        // Keep the user's order, then append any supported sports they haven't
        // ranked (e.g. a sport added in a later release) so the list is complete.
        (stored + DEFAULT_SPORT_ORDER.filterNot { it in stored }).ifEmpty { DEFAULT_SPORT_ORDER }
    }.getOrElse { DEFAULT_SPORT_ORDER }

    private fun readExcludedSports(): Set<String> = runCatching {
        val raw = store.getString(KEY_EXCLUDED_SPORTS, "")
        if (raw.isEmpty()) emptySet() else json.decodeFromString(strListSerializer, raw).toSet()
    }.getOrElse { emptySet() }

    companion object {
        const val PREFS_NAME = "sport_settings"
        const val MAX_FAVORITES = 10

        /** Supported sport keys in their default priority order. Keys match
         *  SportSubject.sport (so "racing" = F1). Existing users get new entries
         *  appended automatically by [readPreferredSports]. */
        val DEFAULT_SPORT_ORDER =
            listOf("soccer", "basketball", "baseball", "football", "hockey", "cricket", "racing")

        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PREF_SPORTS = "preferred_sports"
        private const val KEY_EXCLUDED_SPORTS = "excluded_sports"
    }
}
