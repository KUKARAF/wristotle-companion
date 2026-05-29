package com.lazydevs.wristotle.handlers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "OpenWeatherProvider"

/**
 * [OpenWeatherMap](https://openweathermap.org) implementation of
 * [WeatherProvider]. Requires a **user-supplied API key** (free tier, 60
 * calls/min, 1M calls/month). Selected via the Weather settings card; users
 * who haven't pasted a key still get open-meteo as the default.
 *
 * Behaviour mirrors [OpenMeteoProvider]: two-pass geocode (drop trailing
 * qualifier on miss, prefer state/country match), one current-weather call,
 * `Dispatchers.IO`, 8 s HTTP timeouts. The notable difference is that 401
 * responses surface as [WeatherResult.BadKey] so the handler can show
 * "check your API key" rather than the generic network message.
 */
class OpenWeatherProvider(private val apiKey: String) : WeatherProvider {

    override suspend fun currentWeather(location: WeatherLocation, unit: TempUnit): WeatherResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) {
                return@withContext WeatherResult.BadKey("no API key set")
            }
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

    /** Same two-pass pattern as [OpenMeteoProvider.geocode] — see the doc
     *  there for the rationale. */
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

    private fun geocodePass(searchTokens: List<String>, qualifier: String): GeoHit? {
        val query = searchTokens.joinToString(" ")
        val limit = if (qualifier.isEmpty()) 1 else 10
        val url = "https://api.openweathermap.org/geo/1.0/direct?limit=$limit&q=" +
            URLEncoder.encode(query, "UTF-8") +
            "&appid=" + URLEncoder.encode(apiKey, "UTF-8")
        val body = httpGet(url) ?: return null
        val results = runCatching { JSONArray(body) }.getOrNull() ?: return null
        if (results.length() == 0) return null

        val r = (if (qualifier.isNotEmpty()) pickByQualifier(results, qualifier) else null)
            ?: results.getJSONObject(0)

        return GeoHit(
            coords = WeatherLocation.Coords(r.getDouble("lat"), r.getDouble("lon")),
            displayName = listOfNotNull(
                r.optString("name").ifEmpty { null },
                r.optString("state").ifEmpty { null },
            ).joinToString(", "),
        )
    }

    private fun pickByQualifier(results: JSONArray, qualifier: String): JSONObject? {
        val q = qualifier.lowercase()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val state = r.optString("state").lowercase()
            val country = r.optString("country").lowercase()
            if (state.isNotEmpty() && state.contains(q)) return r
            // country is a 2-letter ISO code — exact match only.
            if (country.isNotEmpty() && country == q) return r
        }
        return null
    }

    // ── Current weather ─────────────────────────────────────────────────────

    private fun fetchCurrent(coords: WeatherLocation.Coords, unit: TempUnit, place: String): WeatherResult {
        val units = if (unit == TempUnit.FAHRENHEIT) "imperial" else "metric"
        val url = "https://api.openweathermap.org/data/2.5/weather?" +
            "lat=${coords.lat}&lon=${coords.lon}&units=$units" +
            "&appid=" + URLEncoder.encode(apiKey, "UTF-8")
        val body = httpGetWithStatus(url) ?: return WeatherResult.Network

        val (status, payload) = body
        if (status == 401) return WeatherResult.BadKey("API key rejected (401)")
        if (status !in 200..299 || payload == null) return WeatherResult.Network

        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return WeatherResult.Network
        val main = json.optJSONObject("main") ?: return WeatherResult.Network
        val weatherArr = json.optJSONArray("weather") ?: return WeatherResult.Network
        if (weatherArr.length() == 0) return WeatherResult.Network
        val first = weatherArr.getJSONObject(0)
        val condition = first.optString("main").ifEmpty { "Unknown" }
        val temp = main.optDouble("temp", Double.NaN)
        if (temp.isNaN()) return WeatherResult.Network
        // Prefer the city name OpenWeather echoes back for places, fall back
        // to whatever we resolved in geocoding when only coords were supplied.
        val resolvedPlace = json.optString("name").ifEmpty { place }
        return WeatherResult.Ok(
            temperature = temp,
            condition = condition,
            place = resolvedPlace,
            unit = unit,
        )
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    /** GET → response body on 2xx, null on any other status. Used for the
     *  geocoding endpoint where we don't care about the specific status. */
    private fun httpGet(url: String): String? = httpGetWithStatus(url)?.takeIf { it.first in 200..299 }?.second

    /** GET that returns (status, body) so the caller can distinguish 401
     *  (bad API key) from 5xx (network blip). Body may be null on read
     *  failures, even with a 2xx. */
    private fun httpGetWithStatus(url: String): Pair<Int, String?>? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Wristotle/companion")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            code to body
        } catch (e: IOException) {
            Log.w(TAG, "HTTP $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
