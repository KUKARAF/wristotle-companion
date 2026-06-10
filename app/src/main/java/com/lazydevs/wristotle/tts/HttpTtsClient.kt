// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.util.Log
import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings
import com.lazydevs.wristotle.speech.nlu.tts.TtsProvider
import com.lazydevs.wristotle.speech.nlu.tts.TtsResult
import com.lazydevs.wristotle.speech.util.SimpleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "HttpTtsClient"

/**
 * OpenAI-compatible `/audio/speech` client. Same single-config shape as
 * `HttpRecognizer` on the STT side — base URL + bearer key + model + voice
 * + (always-WAV) format covers OpenAI itself, OpenedAI Speech, LiteLLM
 * proxies in front of Piper / Coqui, and friends.
 *
 * Returns null on any failure (settings incomplete, network, 4xx/5xx,
 * malformed response). [CompositeTtsProvider] uses that null to decide
 * whether to fall back to the local engine.
 */
class HttpTtsClient(private val settings: TtsProviderSettings) : TtsProvider {

    override val displayName: String = "HTTP TTS"

    override suspend fun synthesizeToWav(text: String): TtsResult = withContext(Dispatchers.IO) {
        val baseUrl = settings.httpBaseUrl.value
        if (baseUrl.isBlank()) {
            return@withContext TtsResult.Failure("base URL not set")
        }
        // Accept either shape: bare base (e.g. `https://api.openai.com/v1`)
        // OR the full endpoint (`…/v1/audio/speech`). The full-endpoint form
        // is a common UX trap — user copies the example from the provider's
        // docs and we'd otherwise double-append → 404. Treat both as the
        // same thing.
        val trimmed = baseUrl.trimEnd('/')
        val url = if (trimmed.endsWith("/audio/speech")) trimmed
                  else "$trimmed/audio/speech"
        val model = settings.httpModel.value.ifBlank { "tts-1" }
        val voice = settings.httpVoice.value.ifBlank { "alloy" }
        val payload = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "wav")
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "audio/wav")
            val apiKey = settings.httpApiKey.value
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }
        val result = SimpleHttp.requestBytes(
            url = url,
            method = "POST",
            headers = headers,
            body = payload.toString().toByteArray(Charsets.UTF_8),
        )
        if (result == null) {
            Log.w(TAG, "transport failure to $url")
            return@withContext TtsResult.Failure("network failure to $url")
        }
        val (code, bytes, errText) = result
        if (code !in 200..299) {
            val excerpt = errText?.take(160)?.replace('\n', ' ')?.trim()
            val detail = providerErrorDetail(excerpt)
            Log.w(TAG, "$code from $url: $excerpt")
            return@withContext TtsResult.Failure("HTTP $code at $url — $detail")
        }
        if (bytes == null || bytes.isEmpty()) {
            return@withContext TtsResult.Failure("HTTP $code at $url returned no body")
        }
        TtsResult.Success(bytes)
    }

    /**
     * Pull a short user-facing reason from OpenAI / OpenedAI Speech /
     * LiteLLM error bodies. They all return JSON like
     * `{"error":{"message":"…","code":"…"}}`; fall back to the raw
     * excerpt if we can't parse it.
     */
    private fun providerErrorDetail(excerpt: String?): String {
        if (excerpt.isNullOrBlank()) return "no response body"
        return runCatching {
            val obj = JSONObject(excerpt)
            val err = obj.optJSONObject("error")
            val msg = err?.optString("message")?.takeIf { it.isNotBlank() }
            val code = err?.optString("code")?.takeIf { it.isNotBlank() }
            when {
                msg != null && code != null -> "$msg ($code)"
                msg != null -> msg
                code != null -> code
                else -> excerpt
            }
        }.getOrDefault(excerpt)
    }
}
