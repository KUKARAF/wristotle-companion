// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.util

import com.lazydevs.wristotle.logging.WristotleLog as Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SimpleHttp"

/**
 * One-shot HTTP helper for the small JSON-API clients in the project
 * (LLM agent providers, weather providers, the speech-to-text
 * `HttpRecognizer`'s multipart upload). Lives in `:speech` so both `:app`
 * consumers and `HttpRecognizer` (which can't depend on `:app`) reach it
 * through the same surface.
 *
 * Not a replacement for [com.lazydevs.wristotle.speech.model.ResumableDownloader]
 * — that's a different shape (Range, manual redirect following, streamed
 * to disk). Keep this helper deliberately small.
 */
object SimpleHttp {

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
     *
     * @param body raw request body bytes, or `null` for GETs / bodyless
     *        verbs. Callers serialising JSON use the String overload
     *        below; multipart / binary uploaders pass the framed bytes
     *        directly.
     */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
    ): Pair<Int, String?>? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", WRISTOTLE_USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                // Fixed-length streaming avoids chunked transfer-encoding,
                // which some self-hosted reverse proxies (caddy, older
                // nginx) reject on multipart uploads. No-op for small JSON
                // bodies; matters for the speech-to-text WAV upload path.
                setFixedLengthStreamingMode(body.size)
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { it.write(body) }
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

    /**
     * Convenience overload for JSON / text bodies. Encodes [body] as
     * UTF-8 and delegates to the canonical [ByteArray] entry point.
     */
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
    ): Pair<Int, String?>? = request(
        url = url,
        method = method,
        headers = headers,
        body = body.toByteArray(Charsets.UTF_8),
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )
}