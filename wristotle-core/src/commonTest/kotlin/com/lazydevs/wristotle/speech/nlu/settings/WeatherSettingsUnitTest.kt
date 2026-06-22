// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WeatherSettings can't use the shared [getEnum] for the unit (its default is a
 * lazy locale-based lambda), so it hand-rolls the empty→locale-default and
 * bad-name→locale-default branches. Those are what this covers.
 */
class WeatherSettingsUnitTest {

    @Test fun emptyStoreUsesLocaleDefault() {
        val s = WeatherSettings(InMemoryKeyValueStore(), localeDefaultProvider = { TempUnit.FAHRENHEIT })
        assertEquals(TempUnit.FAHRENHEIT, s.unit.value)
    }

    @Test fun validStoredUnitWinsOverLocaleDefault() {
        val store = InMemoryKeyValueStore().apply { putString("unit", "CELSIUS") }
        val s = WeatherSettings(store, localeDefaultProvider = { TempUnit.FAHRENHEIT })
        assertEquals(TempUnit.CELSIUS, s.unit.value)
    }

    @Test fun unknownStoredUnitFallsBackToLocaleDefault() {
        val store = InMemoryKeyValueStore().apply { putString("unit", "KELVIN") }
        val s = WeatherSettings(store, localeDefaultProvider = { TempUnit.CELSIUS })
        assertEquals(TempUnit.CELSIUS, s.unit.value)
    }
}
