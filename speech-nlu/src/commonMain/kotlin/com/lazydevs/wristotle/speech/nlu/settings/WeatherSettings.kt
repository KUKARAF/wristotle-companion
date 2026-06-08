// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which weather backend the user has chosen. */
enum class WeatherProviderId { OPEN_METEO, OPEN_WEATHER }

/**
 * Companion-local settings for the Weather feature:
 *
 *  - **Unit** (°C / °F) — default derived from the system locale by the
 *    injected [localeDefaultProvider] (US / LR / MM → °F, else °C);
 *    user-overridable.
 *  - **Provider** — open-meteo (default, no key, free) or OpenWeather
 *    (requires a free-tier API key the user pastes in).
 *  - **OpenWeather API key** — only consulted when provider == OPEN_WEATHER.
 *
 * Backed by [KeyValueStore] (not Room or the watch settings mirror —
 * weather is entirely companion-resolved, no wire keys to keep in sync).
 * Exposes [StateFlow]s so the Settings card can observe + write cleanly.
 *
 * R3 batch 6 — lifted from :app. The Locale lookup stays in :app's
 * `WeatherProvider.kt`, passed in here as the [localeDefaultProvider]
 * lambda.
 */
class WeatherSettings(
    private val store: KeyValueStore,
    private val localeDefaultProvider: () -> TempUnit,
) {

    private val _unit = MutableStateFlow(readUnit())
    val unit: StateFlow<TempUnit> = _unit

    private val _provider = MutableStateFlow(readProvider())
    val provider: StateFlow<WeatherProviderId> = _provider

    private val _apiKey = MutableStateFlow(readApiKey())
    val apiKey: StateFlow<String> = _apiKey

    fun setUnit(value: TempUnit) {
        if (_unit.value == value) return
        store.putString(KEY_UNIT, value.name)
        _unit.value = value
    }

    fun setProvider(value: WeatherProviderId) {
        if (_provider.value == value) return
        store.putString(KEY_PROVIDER, value.name)
        _provider.value = value
    }

    fun setApiKey(value: String) {
        val trimmed = value.trim()
        if (_apiKey.value == trimmed) return
        store.putString(KEY_API_KEY, trimmed)
        _apiKey.value = trimmed
    }

    private fun readUnit(): TempUnit {
        val name = store.getString(KEY_UNIT, "")
        if (name.isEmpty()) return localeDefaultProvider()
        return runCatching { enumValueOf<TempUnit>(name) }
            .getOrElse { localeDefaultProvider() }
    }

    private fun readProvider(): WeatherProviderId {
        val name = store.getString(KEY_PROVIDER, "")
        if (name.isEmpty()) return WeatherProviderId.OPEN_METEO
        return runCatching { enumValueOf<WeatherProviderId>(name) }
            .getOrDefault(WeatherProviderId.OPEN_METEO)
    }

    private fun readApiKey(): String = store.getString(KEY_API_KEY, "").trim()

    companion object {
        /** SharedPreferences file name the Android-side store uses. */
        const val PREFS_NAME = "weather_settings"
        private const val KEY_UNIT = "unit"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API_KEY = "openweather_api_key"
    }
}
