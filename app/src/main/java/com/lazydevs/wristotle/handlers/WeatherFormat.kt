// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.settings.TempUnit
import kotlin.math.roundToInt

/**
 * Pure weather presentation, extracted from [WeatherHandler] so it's
 * unit-testable without the Android-coupled handler (PhoneLocation, providers).
 * Behaviour-preserving; output strings + the US-0x1F card payload are unchanged.
 */
internal object WeatherFormat {

    /** Unit separator (0x1F) the watch's weather widget splits the card on. */
    private const val US = ""

    /** Compact card payload: place ⏐ temp ⏐ condition ⏐ humidity ⏐ wind
     *  (empty field when humidity / wind is absent). */
    fun cardData(w: WeatherResult.Ok): String {
        val tempSymbol = if (w.unit == TempUnit.FAHRENHEIT) "°F" else "°C"
        val windUnit = if (w.unit == TempUnit.FAHRENHEIT) "mph" else "km/h"
        val humidity = w.humidity?.let { "$it%" } ?: ""
        val wind = w.windSpeed?.let { "${it.roundToInt()} $windUnit" } ?: ""
        return listOf(
            w.place,
            "${w.temperature.roundToInt()}$tempSymbol",
            w.condition,
            humidity,
            wind,
        ).joinToString(US)
    }

    /** The plain watch-chat line (also used as the card's fallback text). */
    fun line(result: WeatherResult): String = when (result) {
        is WeatherResult.Ok -> {
            val symbol = if (result.unit == TempUnit.FAHRENHEIT) "°F" else "°C"
            "${result.temperature.roundToInt()}$symbol in ${result.place}\n${result.condition}"
        }
        is WeatherResult.NotFound -> "Couldn't find that place."
        is WeatherResult.Network -> "Couldn't fetch the weather."
        is WeatherResult.BadKey -> "Couldn't fetch the weather: ${result.message}"
    }
}
