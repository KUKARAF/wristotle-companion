package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.nlu.slots.weatherLocation
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import kotlin.math.roundToInt

/**
 * Handles [Intent.Weather] — fetches the current weather from a
 * [WeatherProvider] and renders a short, watch-friendly line.
 *
 * **Phase A:** location slot required. Bare *"what's the weather"* returns
 * a hint to specify a city. Phase B will wire `PhoneLocation` so the bare
 * form can fall back to the phone's last-known location.
 *
 * Unit is the locale default for Phase A; Phase C reads it from
 * `WeatherSettings`.
 */
class WeatherHandler(
    private val provider: WeatherProvider,
    private val unitProvider: () -> TempUnit = ::localeDefaultTempUnit,
) : ActionHandler {

    override val tag: String = "weather"
    override val intent: Intent = Intent.Weather

    override suspend fun handle(result: IntentResult): String {
        val place = result.slots.weatherLocation()
            ?: return BARE_QUERY_HINT
        return format(provider.currentWeather(WeatherLocation.Place(place), unitProvider()))
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
        // Phase A hint — Phase B replaces this with a last-known-location
        // attempt, falling back to a similar message when no location is
        // available or the permission isn't granted.
        const val BARE_QUERY_HINT = "Say \"weather in <city>\"."
    }
}
