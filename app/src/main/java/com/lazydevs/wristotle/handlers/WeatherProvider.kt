// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.settings.TempUnit

/**
 * Provider-agnostic interface for fetching the current weather. The handler
 * stays decoupled from the wire protocol of any specific service — Phase A
 * ships with [OpenMeteoProvider]; Phase C adds an OpenWeather provider that
 * uses a user-supplied API key.
 *
 * All implementations are **suspending** and must dispatch their network +
 * parse work onto [kotlinx.coroutines.Dispatchers.IO] themselves.
 */
interface WeatherProvider {
    suspend fun currentWeather(location: WeatherLocation, unit: TempUnit): WeatherResult
}

/** Where to look weather up — either a spoken place (needs geocoding) or
 *  already-resolved coordinates (used when the phone supplied last-known). */
sealed class WeatherLocation {
    data class Place(val name: String) : WeatherLocation()
    data class Coords(val lat: Double, val lon: Double) : WeatherLocation()
}

/** Normalised result the handler renders without caring which provider ran. */
sealed class WeatherResult {
    /** Success — temperature, a short condition word, and the place name the
     *  user will see ("It's 23°C in Tokyo, cloudy"). [unit] is echoed back so
     *  the handler can format `°C` vs `°F` without re-reading settings. */
    data class Ok(
        val temperature: Double,
        val condition: String,
        val place: String,
        val unit: TempUnit,
        /** Relative humidity %, when the provider supplies it. */
        val humidity: Int? = null,
        /** Wind speed in the unit system's native unit (km/h for metric,
         *  mph for imperial), when available. */
        val windSpeed: Double? = null,
    ) : WeatherResult()

    /** Geocoding returned no results (the spoken place doesn't exist in the
     *  provider's gazetteer). */
    data object NotFound : WeatherResult()

    /** Network failure — no internet, DNS fail, timeout, non-2xx response,
     *  malformed JSON. The handler surfaces a generic "couldn't fetch" line. */
    data object Network : WeatherResult()

    /** Provider rejected the API key (401 / quota). Only OpenWeather can
     *  return this; open-meteo has no key. */
    data class BadKey(val message: String) : WeatherResult()
}