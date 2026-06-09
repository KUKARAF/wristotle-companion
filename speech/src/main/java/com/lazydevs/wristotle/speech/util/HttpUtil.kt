// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.util

import org.json.JSONObject

/**
 * Shared HTTP helpers for the small JSON-API clients in the project
 * (LLM agent providers in `:app/agent`, the speech-to-text
 * `HttpRecognizer` in `:speech`). Lives in `:speech` so both consumers
 * can reach it without `:app` ↔ `:speech` cycles.
 */

/** User-Agent header value sent on every outbound HTTP request. */
const val WRISTOTLE_USER_AGENT = "Wristotle/companion"

/**
 * Coarse failure classification for non-2xx HTTP responses, shared by
 * every client that maps `HttpURLConnection` status codes onto its own
 * typed failure shape. Each caller picks its own target type for the
 * bucket — the LLM clients fan out to `LlmResult.Failure.*`, the speech
 * client fans out to `SpeechRecognizer.ERROR_*`.
 */
enum class HttpFailureBucket {
    /** 401 / 403 — bad or missing credentials. */
    Auth,
    /** 429 — provider asked us to back off. */
    RateLimit,
    /** 5xx — provider failed on its end. */
    Server,
    /** Anything else outside 2xx. */
    Other,
}

fun bucketFor(status: Int): HttpFailureBucket = when {
    status == 401 || status == 403 -> HttpFailureBucket.Auth
    status == 429 -> HttpFailureBucket.RateLimit
    status in 500..599 -> HttpFailureBucket.Server
    else -> HttpFailureBucket.Other
}

/**
 * Best-effort extraction of a provider's error message from a response
 * body. Tries `.error.message` (Anthropic + OpenAI shape), then a flat
 * `.error` string (some OpenAI-compat servers). Returns `null` on any
 * parse failure so callers can fall back to a generic message.
 *
 * The two shapes are dispatched on the actual type of `.error` —
 * otherwise an `error` object with a blank `message` would fall through
 * to the flat-string branch, where `optString("error")` stringifies the
 * inner JSON object and surfaces e.g. `{"message":""}` as the "error
 * message" displayed to the user.
 *
 * **Sibling impl note:** the multiplatform LLM clients in
 * `:wristotle-core/.../speech/nlu/http/ProviderErrorMessage.kt` carry
 * an equivalent `providerErrorMessage` written against
 * `kotlinx.serialization.json` (since commonMain can't reach `org.json`).
 * The two MUST stay semantically aligned — change one, change the other.
 * The duplication is structural: `:wristotle-core/androidMain` already
 * depends on `:speech` (via `ModelFileStorage`), so `:speech` can't
 * depend back on `:wristotle-core` without breaking that edge first.
 */
fun String?.providerErrorMessage(): String? {
    if (this == null) return null
    return runCatching {
        when (val errorField = JSONObject(this).opt("error")) {
            is JSONObject -> errorField.optString("message").ifBlank { null }
            is String -> errorField.ifBlank { null }
            else -> null
        }
    }.getOrNull()
}