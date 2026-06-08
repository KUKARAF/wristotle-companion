// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.http

import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.http.HttpRequest
import com.lazydevs.wristotle.speech.nlu.http.HttpResponse
import com.lazydevs.wristotle.speech.util.SimpleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android impl of the multiplatform [HttpClient] seam. Delegates to
 * the JVM-only `SimpleHttp` (HttpURLConnection wrapper) and off-loads
 * to [Dispatchers.IO] so commonMain LLM clients can stay
 * dispatcher-agnostic.
 *
 */
class AndroidHttpClient : HttpClient {
    override suspend fun request(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connectMs = if (request.timeoutMs > 0) request.timeoutMs else 10_000
        val readMs = if (request.timeoutMs > 0) request.timeoutMs else 30_000
        val result = SimpleHttp.request(
            url = request.url,
            method = request.method,
            headers = request.headers,
            body = request.body,
            connectTimeoutMs = connectMs,
            readTimeoutMs = readMs,
        )
        if (result == null) {
            HttpResponse(status = 0, body = "", error = "transport failure")
        } else {
            HttpResponse(status = result.first, body = result.second ?: "")
        }
    }
}
