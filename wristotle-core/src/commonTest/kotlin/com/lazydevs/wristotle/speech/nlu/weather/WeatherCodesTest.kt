// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package lazydevs.wristotle.speech.nlu.weather

import com.lazydevs.wristotle.speech.nlu.weather.WeatherCodes
import kotlin.test.assertEquals
import kotlin.test.Test

class WeatherCodesTest {

    @Test fun `clear is mapped`() {
        assertEquals("Clear", WeatherCodes.describe(0))
    }

    @Test fun `partly cloudy is mapped`() {
        assertEquals("Partly cloudy", WeatherCodes.describe(2))
    }

    @Test fun `light rain is mapped`() {
        assertEquals("Light rain", WeatherCodes.describe(61))
    }

    @Test fun `thunderstorm is mapped`() {
        assertEquals("Thunderstorm", WeatherCodes.describe(95))
    }

    @Test fun `unknown code does not leak the number`() {
        assertEquals("Unknown", WeatherCodes.describe(999))
    }
}