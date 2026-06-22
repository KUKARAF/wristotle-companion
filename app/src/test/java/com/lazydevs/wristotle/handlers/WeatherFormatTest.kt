// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.settings.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure weather presentation (extracted from WeatherHandler). Guards the
 * five-field US-0x1F card payload — including the empty-field handling for a
 * missing humidity / wind, which silently breaks the watch weather card if it
 * regresses — the unit selection, and the error lines.
 */
class WeatherFormatTest {

    private val US = ""

    @Test fun `card data joins all five fields with the US separator`() {
        val w = WeatherResult.Ok(
            temperature = 22.6, condition = "Sunny", place = "Tokyo",
            unit = TempUnit.CELSIUS, humidity = 65, windSpeed = 9.7,
        )
        assertEquals("Tokyo${US}23°C${US}Sunny${US}65%${US}10 km/h", WeatherFormat.cardData(w))
    }

    @Test fun `missing humidity and wind leave empty fields (not dropped)`() {
        val w = WeatherResult.Ok(20.0, "Cloudy", "London", TempUnit.CELSIUS, humidity = null, windSpeed = null)
        // Five fields still present so the watch's split lands on the right indices.
        assertEquals("London${US}20°C${US}Cloudy$US$US", WeatherFormat.cardData(w))
    }

    @Test fun `fahrenheit uses degF and mph`() {
        val w = WeatherResult.Ok(72.0, "Clear", "Austin", TempUnit.FAHRENHEIT, humidity = 40, windSpeed = 8.0)
        assertEquals("Austin${US}72°F${US}Clear${US}40%${US}8 mph", WeatherFormat.cardData(w))
    }

    @Test fun `line renders temp, place and condition`() {
        assertEquals(
            "18°C in Paris\nRain",
            WeatherFormat.line(WeatherResult.Ok(18.4, "Rain", "Paris", TempUnit.CELSIUS)),
        )
    }

    @Test fun `error results render their messages`() {
        assertEquals("Couldn't find that place.", WeatherFormat.line(WeatherResult.NotFound))
        assertEquals("Couldn't fetch the weather.", WeatherFormat.line(WeatherResult.Network))
        assertEquals("Couldn't fetch the weather: bad key", WeatherFormat.line(WeatherResult.BadKey("bad key")))
    }
}
