// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.sportskapi.SportSubject
import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the Restore-path [SportSettings.mergeFavorites] union semantics. */
class SportSettingsTest {

    private fun subj(id: String, name: String = id, sport: String = "soccer", league: String = "eng.1") =
        SportSubject(id = id, name = name, sport = sport, league = league)

    @Test fun merge_intoEmpty_addsAll() {
        val s = SportSettings(InMemoryKeyValueStore())
        s.mergeFavorites(listOf(subj("a"), subj("b")))
        assertEquals(listOf("a", "b"), s.favorites.value.map { it.id })
    }

    @Test fun merge_dedupesById_keepingExistingFirst() {
        val s = SportSettings(InMemoryKeyValueStore())
        s.addFavorite(subj("a"))
        s.mergeFavorites(listOf(subj("a"), subj("b")))
        // 'a' already present (not duplicated); 'b' appended after it.
        assertEquals(listOf("a", "b"), s.favorites.value.map { it.id })
    }

    @Test fun merge_capsAtMaxFavorites() {
        val s = SportSettings(InMemoryKeyValueStore())
        val incoming = (1..(SportSettings.MAX_FAVORITES + 5)).map { subj("t$it") }
        s.mergeFavorites(incoming)
        assertEquals(SportSettings.MAX_FAVORITES, s.favorites.value.size)
    }

    @Test fun merge_emptyIncoming_isNoOp() {
        val s = SportSettings(InMemoryKeyValueStore())
        s.addFavorite(subj("a"))
        s.mergeFavorites(emptyList())
        assertEquals(listOf("a"), s.favorites.value.map { it.id })
    }

    @Test fun excludeAndReenableSport() {
        val store = InMemoryKeyValueStore()
        val s = SportSettings(store)
        assertEquals(emptySet(), s.excludedSports.value)
        s.setSportEnabled("cricket", false)
        assertEquals(setOf("cricket"), s.excludedSports.value)
        // Persists across instances.
        assertEquals(setOf("cricket"), SportSettings(store).excludedSports.value)
        s.setSportEnabled("cricket", true)
        assertEquals(emptySet(), s.excludedSports.value)
    }

    @Test fun merge_persistsAcrossNewInstance() {
        val store = InMemoryKeyValueStore()
        SportSettings(store).mergeFavorites(listOf(subj("a"), subj("b")))
        // A fresh instance reads the same backing store.
        assertEquals(listOf("a", "b"), SportSettings(store).favorites.value.map { it.id })
    }
}
