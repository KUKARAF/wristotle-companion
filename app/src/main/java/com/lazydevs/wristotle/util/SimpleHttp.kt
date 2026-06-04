package com.lazydevs.wristotle.util

import android.util.Log
import com.lazydevs.wristotle.speech.util.WRISTOTLE_USER_AGENT
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot HTTP helper for the four small JSON-API clients we have
 * (Anthropic + OpenAI-compat LLM, open-meteo + OpenWeather). Each used
 * to repeat the same `URL.openConnection() as HttpURLConnection` /
 * inputStream-vs-errorStream / `disconnect()` shape.
 *
 * Not a replacement for [com.lazydevs.wristotle.speech.model.ResumableDownloader]
 * — that's a different shape (Range, manual redirect following, streamed
 * to disk). Keep this helper deliberately small.
 */
object SimpleHttp {
    private const val TAG = "SimpleHttp"

    /**
     * Run one HTTP request and return `(status, body)`. `body` comes from
     * `inputStream` on 2xx, `errorStream` otherwise — so callers can pull
     * a provider error message off non-2xx responses.
     *
     * Returns `null` only on transport failure (DNS, connect/read timeout,
     * socket reset). Any HTTP status — including 4xx / 5xx — comes back
     * as a non-null pair so the caller can decide what to do with it.
     *
     * `User-Agent` is set automatically; callers add provider-specific
     * headers (auth, content-type, accept) via [headers].
     */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
    ): Pair<Int, String?>? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", WRISTOTLE_USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) {
                conn.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }
            code to resp
        } catch (e: IOException) {
            Log.w(TAG, "$method $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
