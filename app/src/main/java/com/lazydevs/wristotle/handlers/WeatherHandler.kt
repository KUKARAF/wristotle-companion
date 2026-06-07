// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.nlu.slots.weatherLocation
import com.lazydevs.wristotle.phone.PhoneLocation
import com.lazydevs.wristotle.settings.WeatherProviderId
import com.lazydevs.wristotle.settings.WeatherSettings
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import kotlin.math.roundToInt

/**
 * Handles [Intent.Weather] — fetches the current weather from a
 * [WeatherProvider] and renders a short, watch-friendly line.
 *
 * Two paths:
 *  - **Place named** (*"weather in Tokyo"*) → provider geocodes + fetches.
 *  - **Bare query** (*"what's the weather"*) → tries the phone's last-known
 *    location via [phoneLocation]. Falls back to a permission-specific hint
 *    when no cached fix is available so the user knows whether to grant
 *    Location or just name a city.
 *
 * Unit is the locale default for now; Phase C will read it from a settings
 * card.
 */
class WeatherHandler(
    private val openMeteo: WeatherProvider,
    private val openWeatherFactory: (apiKey: String) -> WeatherProvider,
    private val phoneLocation: PhoneLocation,
    private val settings: WeatherSettings,
) : ActionHandler {

    override val tag: String = "weather"
    override val intent: Intent = Intent.Weather

    override suspend fun handle(result: IntentResult): String {
        val place = result.slots.weatherLocation()
        val location = if (place != null) {
            WeatherLocation.Place(place)
        } else {
            // Bare query — try the phone's cached fix; the messages below
            // name the specific reason the bare path can't proceed so the
            // user knows what to fix.
            val fix = phoneLocation.lastKnown()
            if (fix == null) {
                return if (phoneLocation.hasPermission()) NO_RECENT_LOCATION_HINT
                else NO_PERMISSION_HINT
            }
            WeatherLocation.Coords(fix.latitude, fix.longitude)
        }
        val provider = selectProvider()
        return format(provider.currentWeather(location, settings.unit.value))
    }

    /** Reads the user's chosen provider from settings. The OpenWeather impl
     *  is constructed fresh each call so the freshest API key from settings
     *  always lands — open-meteo is stateless and shared. */
    private fun selectProvider(): WeatherProvider = when (settings.provider.value) {
        WeatherProviderId.OPEN_METEO -> openMeteo
        WeatherProviderId.OPEN_WEATHER -> openWeatherFactory(settings.apiKey.value)
    }

    private fun format(result: WeatherResult): String = when (result) {
        is WeatherResult.Ok -> {
            val symbol = if (result.unit == TempUnit.FAHRENHEIT) "°F" else "°C"
            "${result.temperature.roundToInt()}$symbol in ${result.place}\n${result.condition}"
        }
        is WeatherResult.NotFound -> "Couldn't find that place."
        is WeatherResult.Network -> "Couldn't fetch the weather."
        is WeatherResult.BadKey -> "Couldn't fetch the weather: ${result.message}"
    }

    private companion object {
        const val NO_RECENT_LOCATION_HINT =
            "No recent location.\nTry \"weather in <city>\"."
        const val NO_PERMISSION_HINT =
            "Location not allowed.\nTry \"weather in <city>\"\nor enable it in the app."
    }
}