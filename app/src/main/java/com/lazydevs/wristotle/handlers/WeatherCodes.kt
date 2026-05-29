package com.lazydevs.wristotle.handlers

/**
 * Map from a [WMO 4677](https://www.nodc.noaa.gov/archive/arc0021/0002199/1.1/data/0-data/HTML/WMO-CODE/WMO4677.HTM)
 * weather code (used by open-meteo's `weather_code` field) to a short
 * one- or two-word condition string suitable for the watch chat.
 *
 * Codes the provider can emit are listed below in groups (clear / cloud /
 * fog / drizzle / rain / snow / showers / thunderstorm). Any value not in
 * the map collapses to "Unknown" — better than a numeric leak.
 *
 * Pure — no Android, no provider state.
 */
object WeatherCodes {

    fun describe(code: Int): String = CODE_MAP[code] ?: "Unknown"

    private val CODE_MAP: Map<Int, String> = buildMap {
        put(0, "Clear")
        put(1, "Mostly clear")
        put(2, "Partly cloudy")
        put(3, "Overcast")

        put(45, "Foggy")
        put(48, "Foggy")

        put(51, "Light drizzle")
        put(53, "Drizzle")
        put(55, "Heavy drizzle")
        put(56, "Freezing drizzle")
        put(57, "Freezing drizzle")

        put(61, "Light rain")
        put(63, "Rain")
        put(65, "Heavy rain")
        put(66, "Freezing rain")
        put(67, "Freezing rain")

        put(71, "Light snow")
        put(73, "Snow")
        put(75, "Heavy snow")
        put(77, "Snow grains")

        put(80, "Light showers")
        put(81, "Showers")
        put(82, "Heavy showers")
        put(85, "Snow showers")
        put(86, "Heavy snow showers")

        put(95, "Thunderstorm")
        put(96, "Thunderstorm w/ hail")
        put(99, "Thunderstorm w/ hail")
    }
}
