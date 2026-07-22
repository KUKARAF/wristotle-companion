// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.homeassistant

import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Talks to a self-hosted Home Assistant's conversation API — one POST to
 * `{baseUrl}/api/conversation/process`, which runs HA's own server-side NLU
 * over the spoken text and returns a speech reply. No LLM on our side.
 *
 * HA is an open, self-hostable service with a stable API and a free
 * long-lived access token, so we target it natively (the open-vs-closed
 * integration stance). HTTP goes through the [HttpClient] seam;
 * request/response are kotlinx.serialization.json end-to-end. Auth is a
 * bearer token — never logged (see [HaResult] failure messages, which carry
 * status codes and HA's own error text, never the token).
 *
 * Built per call from [com.lazydevs.wristotle.speech.nlu.settings.HomeAssistantSettings]
 * so a Settings edit takes effect on the next voice command without a restart.
 */
class HomeAssistantClient(
    private val http: HttpClient,
    baseUrl: String,
    private val token: String,
    private val language: String = DEFAULT_LANGUAGE,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    /** Normalised once: trailing slashes trimmed so `http://ha.local:8123/`
     *  and `http://ha.local:8123` both produce a single-slash endpoint. */
    private val baseUrl: String = baseUrl.trim().trimEnd('/')

    /**
     * Forward [command] to HA and return its spoken reply (or a typed
     * failure). [command] is the bare command with the wake-word lead-in
     * already stripped by [com.lazydevs.wristotle.speech.nlu.slots.HomeAssistantSlots].
     */
    suspend fun process(command: String): HaResult {
        if (baseUrl.isBlank()) return HaResult.NotConfigured
        if (token.isBlank()) return HaResult.NotConfigured
        return try {
            val body = buildJsonObject {
                put("text", command)
                put("language", language)
            }.toString()
            val resp = http.request(
                HttpRequest(
                    method = "POST",
                    url = "$baseUrl$CONVERSATION_PATH",
                    headers = buildMap {
                        put("Content-Type", "application/json")
                        put("Accept", "application/json")
                        put("User-Agent", USER_AGENT)
                        put("Authorization", "Bearer $token")
                    },
                    body = body.encodeToByteArray(),
                    timeoutMs = readTimeoutMs,
                ),
            )
            statusToResult(resp.status, resp.body, resp.error)
        } catch (e: Exception) {
            HaResult.Failure.Network(e.message ?: "unknown error")
        }
    }

    private fun statusToResult(status: Int, payload: String, error: String?): HaResult = when {
        status == 0 -> HaResult.Failure.Network(error ?: "connect failed")
        status == 401 || status == 403 -> HaResult.Failure.BadAuth("access token rejected ($status)")
        status == 404 -> HaResult.Failure.Other(
            "conversation endpoint not found (404) — check the URL and that the " +
                "Conversation integration is enabled",
        )
        status !in 200..299 -> HaResult.Failure.Other("HTTP $status")
        payload.isEmpty() -> HaResult.Failure.Network("empty response body")
        else -> parseSuccess(payload)
    }

    /**
     * Pull `response.speech.plain.speech` out of the HA reply. HA always
     * includes a speech string for `action_done` / `query_answer` /
     * `error` response types, so a missing one means an unexpected shape.
     */
    private fun parseSuccess(payload: String): HaResult {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return HaResult.Failure.Network("malformed JSON")
        val speech = (root["response"] as? JsonObject)
            ?.get("speech")?.let { it as? JsonObject }
            ?.get("plain")?.let { it as? JsonObject }
            ?.get("speech")?.let { it as? JsonPrimitive }
            ?.contentOrNull?.trim()
        return if (speech.isNullOrBlank()) HaResult.Failure.Other("Home Assistant returned no reply")
        else HaResult.Success(speech)
    }

    companion object {
        private const val USER_AGENT = "Wristotle/companion"
        private const val CONVERSATION_PATH = "/api/conversation/process"
        const val DEFAULT_LANGUAGE = "en"

        /** Default read timeout. HA's conversation pipeline is local + fast
         *  (typically well under a second); this leaves ample headroom for a
         *  slow LAN / Tailscale hop without risking the watch's 15 s ceiling. */
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
    }
}

/**
 * Typed outcome of a [HomeAssistantClient.process] call. Failure messages
 * carry HTTP status codes and HA's own error text — never the access token.
 */
sealed interface HaResult {
    /** HA accepted the command; [speech] is its spoken reply. */
    data class Success(val speech: String) : HaResult

    /** Base URL or token is blank — the user hasn't set up HA yet. */
    data object NotConfigured : HaResult

    sealed interface Failure : HaResult {
        val message: String

        /** Token rejected (401/403). */
        data class BadAuth(override val message: String) : Failure

        /** Transport-level failure — DNS, connect, timeout, empty body. */
        data class Network(override val message: String) : Failure

        /** Anything else — non-2xx status, missing endpoint, unexpected shape. */
        data class Other(override val message: String) : Failure
    }
}
