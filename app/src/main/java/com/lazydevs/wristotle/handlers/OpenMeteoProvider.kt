// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.weather.WeatherCodes
import android.util.Log
import com.lazydevs.wristotle.speech.nlu.settings.TempUnit
import com.lazydevs.wristotle.speech.util.SimpleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

private const val TAG = "OpenMeteoProvider"

/**
 * [open-meteo.com](https://open-meteo.com) implementation of [WeatherProvider].
 * Free, **no API key**, no registration — the default provider.
 *
 * Two HTTP calls per [WeatherLocation.Place] (geocode then current weather);
 * one per [WeatherLocation.Coords] (current weather only). All work runs on
 * [Dispatchers.IO]; no caching in v1 — open-meteo's free-tier limits are
 * generous for the volume Wristotle would generate.
 */
class OpenMeteoProvider : WeatherProvider {

    override suspend fun currentWeather(location: WeatherLocation, unit: TempUnit): WeatherResult =
        withContext(Dispatchers.IO) {
            try {
                val (coords, place) = when (location) {
                    is WeatherLocation.Place -> {
                        val geo = geocode(location.name)
                            ?: return@withContext WeatherResult.NotFound
                        geo.coords to geo.displayName
                    }
                    is WeatherLocation.Coords -> location to "Your area"
                }
                fetchCurrent(coords, unit, place)
            } catch (e: IOException) {
                Log.w(TAG, "network failure", e)
                WeatherResult.Network
            } catch (e: Exception) {
                Log.w(TAG, "unexpected provider failure", e)
                WeatherResult.Network
            }
        }

    // ── Geocoding ────────────────────────────────────────────────────────────

    private data class GeoHit(val coords: WeatherLocation.Coords, val displayName: String)

    /**
     * Two-pass lookup against open-meteo's gazetteer.
     *
     * The gazetteer matches against the city's `name` field only — a query
     * like *"mountain view california"* finds zero hits because no city has
     * that literal name. So:
     *
     *  1. Try the full normalised string with `count=1` (covers the common
     *     "san francisco" / "tokyo" cases in one call).
     *  2. If that returned zero, drop the **last** token (likely a state /
     *     country qualifier) and retry with `count=10`. When the dropped
     *     token matches a returned result's `admin1` / `country` /
     *     `country_code`, prefer that disambiguated hit — *"mountain view
     *     california"* now picks Mountain View, CA over the first
     *     population-ordered hit; *"paris texas"* picks Paris, TX over
     *     Paris, France.
     *
     * Commas / extra whitespace are normalised away first so *"mountain
     *  view, california"* takes the same path. Returns null only when both
     *  passes come back empty (handler reports `NotFound`).
     */
    private fun geocode(spoken: String): GeoHit? {
        val normalized = spoken
            .lowercase()
            .replace(',', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null
        val tokens = normalized.split(' ')

        geocodePass(tokens, qualifier = "")?.let { return it }
        if (tokens.size <= 1) return null
        return geocodePass(tokens.dropLast(1), qualifier = tokens.last())
    }

    /** One round trip with a given token list. When [qualifier] is non-empty,
     *  scans the returned results for one whose `admin1` / `country` /
     *  `country_code` matches the qualifier (case-insensitive) and picks it
     *  in preference to the first population-ordered hit. */
    private fun geocodePass(searchTokens: List<String>, qualifier: String): GeoHit? {
        val query = searchTokens.joinToString(" ")
        val count = if (qualifier.isEmpty()) 1 else 10
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=$count&language=en&name=" +
            URLEncoder.encode(query, "UTF-8")
        val body = httpGet(url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val r = (if (qualifier.isNotEmpty()) pickByQualifier(results, qualifier) else null)
            ?: results.getJSONObject(0)

        return GeoHit(
            coords = WeatherLocation.Coords(r.getDouble("latitude"), r.getDouble("longitude")),
            // Prefer the canonical name; admin1 (state/region) added when present
            // so "Springfield, Illinois" beats a bare "Springfield".
            displayName = listOfNotNull(
                r.optString("name").ifEmpty { null },
                r.optString("admin1").ifEmpty { null },
            ).joinToString(", "),
        )
    }

    private fun pickByQualifier(results: JSONArray, qualifier: String): JSONObject? {
        val q = qualifier.lowercase()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val admin1 = r.optString("admin1").lowercase()
            val country = r.optString("country").lowercase()
            val code = r.optString("country_code").lowercase()
            if (admin1.isNotEmpty() && admin1.contains(q)) return r
            if (country.isNotEmpty() && country.contains(q)) return r
            if (code.isNotEmpty() && code == q) return r
        }
        return null
    }

    // ── Current weather ─────────────────────────────────────────────────────

    private fun fetchCurrent(coords: WeatherLocation.Coords, unit: TempUnit, place: String): WeatherResult {
        val tempUnit = if (unit == TempUnit.FAHRENHEIT) "fahrenheit" else "celsius"
        val windUnit = if (unit == TempUnit.FAHRENHEIT) "mph" else "kmh"
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=${coords.lat}&longitude=${coords.lon}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&temperature_unit=$tempUnit" +
            "&wind_speed_unit=$windUnit" +
            "&timezone=auto"
        val body = httpGet(url) ?: return WeatherResult.Network
        val current = JSONObject(body).optJSONObject("current") ?: return WeatherResult.Network
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val code = current.optInt("weather_code", -1)
        if (temp.isNaN() || code < 0) return WeatherResult.Network
        return WeatherResult.Ok(
            temperature = temp,
            condition = WeatherCodes.describe(code),
            place = place,
            unit = unit,
            humidity = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
            windSpeed = current.optDouble("wind_speed_10m", Double.NaN).takeIf { !it.isNaN() },
        )
    }

    /** 8 s timeouts so the watch's 15 s response watchdog never fires
     *  before this returns. Non-2xx absorbed as a geocode/lookup miss. */
    private fun httpGet(url: String): String? {
        val (status, body) = SimpleHttp.request(
            url = url,
            headers = mapOf("Accept" to "application/json"),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 8_000,
        ) ?: return null
        if (status !in 200..299) {
            Log.w(TAG, "HTTP $status from $url")
            return null
        }
        return body
    }
}