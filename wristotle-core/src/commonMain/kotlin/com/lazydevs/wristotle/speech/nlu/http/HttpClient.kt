// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.http

/**
 * Minimal HTTP client surface used by the lifted LLM agent clients
 * (Anthropic / OpenAI-compatible) + every small JSON API the project
 * talks to (weather providers, MCP integrations).
 *
 * The Android JVM impl wraps `java.net.HttpURLConnection` (see
 * `:speech/.../SimpleHttp`); an iOS impl would wrap `URLSession`. Both
 * are inherently blocking from a single suspend call's point of view,
 * so the platform impl is responsible for off-loading to the
 * appropriate dispatcher.
 *
 * R4 batch 9 — interface added. The LLM clients
 * (AnthropicLlmClient / OpenAiCompatibleLlmClient) currently live in
 * :app/agent and call SimpleHttp directly; refactoring them through
 * this interface is a follow-on lift (~500 lines of JSON parsing).
 */
interface HttpClient {

    /**
     * Run one HTTP request and return [HttpResponse]. The body is
     * captured from the success stream on 2xx and from the error
     * stream otherwise, so callers can pull provider error messages
     * off non-2xx replies.
     */
    suspend fun request(request: HttpRequest): HttpResponse
}

/**
 * Pure-data description of a single HTTP request.
 *
 * - [method] — "GET", "POST", "PUT", "DELETE". Case-sensitive on the
 *   wire but the impl uppercases.
 * - [url] — absolute URL including any query string.
 * - [headers] — map of name → value pairs. The impl is responsible
 *   for setting `Content-Length` etc.; callers handle `Authorization`,
 *   `Accept`, `Content-Type`.
 * - [body] — raw bytes (null = no body, e.g. GET). The impl writes
 *   exactly these bytes.
 * - [timeoutMs] — both connect and read timeout; 0 = platform default.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = 0,
)

/**
 * Pure-data response. [status] is the HTTP status code (0 when the
 * request failed before a response was received — DNS, connect,
 * timeout); [body] is the response body as a UTF-8 string (best effort
 * for non-text responses). [error] carries the platform's error
 * message when status is 0.
 */
data class HttpResponse(
    val status: Int,
    val body: String,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = status in 200..299
}
