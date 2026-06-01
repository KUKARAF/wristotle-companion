package com.lazydevs.wristotle.settings

import android.content.Context
import android.content.SharedPreferences
import com.lazydevs.wristotle.handlers.TempUnit
import com.lazydevs.wristotle.handlers.localeDefaultTempUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which weather backend the user has chosen. */
enum class WeatherProviderId { OPEN_METEO, OPEN_WEATHER }

/**
 * Companion-local settings for the Weather feature:
 *
 *  - **Unit** (°C / °F) — default derived from the system locale
 *    (US / LR / MM → °F, else °C); user-overridable.
 *  - **Provider** — open-meteo (default, no key, free) or OpenWeather
 *    (requires a free-tier API key the user pastes in).
 *  - **OpenWeather API key** — only consulted when provider == OPEN_WEATHER.
 *
 * Backed by `SharedPreferences` (not Room or the watch settings mirror —
 * weather is entirely companion-resolved, no wire keys to keep in sync).
 * Exposes [StateFlow]s so the Settings card can observe + write cleanly.
 */
class WeatherSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _unit = MutableStateFlow(readUnit())
    val unit: StateFlow<TempUnit> = _unit

    private val _provider = MutableStateFlow(readProvider())
    val provider: StateFlow<WeatherProviderId> = _provider

    private val _apiKey = MutableStateFlow(readApiKey())
    val apiKey: StateFlow<String> = _apiKey

    fun setUnit(value: TempUnit) {
        if (_unit.value == value) return
        prefs.edit().putString(KEY_UNIT, value.name).apply()
        _unit.value = value
    }

    fun setProvider(value: WeatherProviderId) {
        if (_provider.value == value) return
        prefs.edit().putString(KEY_PROVIDER, value.name).apply()
        _provider.value = value
    }

    fun setApiKey(value: String) {
        val trimmed = value.trim()
        if (_apiKey.value == trimmed) return
        prefs.edit().putString(KEY_API_KEY, trimmed).apply()
        _apiKey.value = trimmed
    }

    private fun readUnit(): TempUnit {
        val name = prefs.getString(KEY_UNIT, null) ?: return localeDefaultTempUnit()
        return runCatching { TempUnit.valueOf(name) }.getOrDefault(localeDefaultTempUnit())
    }

    private fun readProvider(): WeatherProviderId {
        val name = prefs.getString(KEY_PROVIDER, null) ?: return WeatherProviderId.OPEN_METEO
        return runCatching { WeatherProviderId.valueOf(name) }
            .getOrDefault(WeatherProviderId.OPEN_METEO)
    }

    private fun readApiKey(): String = prefs.getString(KEY_API_KEY, "")?.trim() ?: ""

    private companion object {
        const val PREFS_NAME = "weather_settings"
        const val KEY_UNIT = "unit"
        const val KEY_PROVIDER = "provider"
        const val KEY_API_KEY = "openweather_api_key"
    }
}
