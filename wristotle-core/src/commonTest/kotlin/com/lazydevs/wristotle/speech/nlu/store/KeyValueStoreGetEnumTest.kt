// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.store

import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

private enum class Fruit { APPLE, BANANA, CHERRY }

/**
 * Guards the shared [getEnum] read path (used by ~7 settings classes). The
 * fallback branch — a stored value that no longer maps to an enum constant
 * (renamed/removed constant, hand-edited prefs, migrated value) — must yield
 * the default rather than throwing, or every consumer mis-reads.
 */
class KeyValueStoreGetEnumTest {

    @Test
    fun unsetKeyReturnsDefault() {
        assertEquals(Fruit.BANANA, InMemoryKeyValueStore().getEnum("k", Fruit.BANANA))
    }

    @Test
    fun emptyStoredValueReturnsDefault() {
        val s = InMemoryKeyValueStore().apply { putString("k", "") }
        assertEquals(Fruit.BANANA, s.getEnum("k", Fruit.BANANA))
    }

    @Test
    fun validStoredNameResolves() {
        val s = InMemoryKeyValueStore().apply { putString("k", "CHERRY") }
        assertEquals(Fruit.CHERRY, s.getEnum("k", Fruit.APPLE))
    }

    @Test
    fun unknownConstantFallsBackToDefault() {
        val s = InMemoryKeyValueStore().apply { putString("k", "DURIAN") }
        assertEquals(Fruit.APPLE, s.getEnum("k", Fruit.APPLE))
    }

    @Test
    fun wrongCaseFallsBack_enumValueOfIsCaseSensitive() {
        val s = InMemoryKeyValueStore().apply { putString("k", "apple") }
        assertEquals(Fruit.CHERRY, s.getEnum("k", Fruit.CHERRY))
    }
}
