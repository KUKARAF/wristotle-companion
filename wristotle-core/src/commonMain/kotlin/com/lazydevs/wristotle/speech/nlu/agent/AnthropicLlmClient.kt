// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.agent

import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.http.HttpRequest
import com.lazydevs.wristotle.speech.nlu.http.providerErrorMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API client.
 *
 * - `complete()` is one-shot Q&A — single user message, no tools.
 * - `chat()` does the full multi-turn + tool-use shape: serialises
 *   [LlmMessage] into Anthropic's `messages[].content` blocks (mixing
 *   `text` / `tool_use` / `tool_result` types) and parses the response
 *   back into [LlmResponse.Ok].
 *
 * Tool results are sent under `role: "user"` with structured content
 * (Anthropic's convention — different from OpenAI which uses a
 * dedicated `role: "tool"`).
 *
 * R5 batch 2 — lifted from :app/agent. HTTP goes through the
 * [HttpClient] seam (Android impl wraps SimpleHttp/HttpURLConnection);
 * response parsing now uses kotlinx.serialization.json end-to-end.
 */
class AnthropicLlmClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /**
     * Opt-in: Anthropic's server-side `web_search_<YYYYMMDD>` tool.
     * Lets Claude search the web mid-reply for fresh facts. Billed per
     * query on Anthropic's side; off by default. Only affects [chat] —
     * [complete] never sends tools.
     */
    private val webSearchEnabled: Boolean = false,
) : LlmClient {

    override suspend fun complete(userQuery: String, systemPrompt: String?): LlmResult {
        if (apiKey.isBlank()) return LlmResult.Failure.NoKey()
        return try {
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", userQuery)
                    })
                })
            }.toString()
            val resp = httpPost(body)
            statusToCompleteResult(resp.status, resp.body, resp.error)
        } catch (e: Exception) {
            LlmResult.Failure.Network(e.message ?: "unknown error")
        }
    }

    override suspend fun chat(messages: List<LlmMessage>, tools: List<LlmTool>): LlmResponse {
        if (apiKey.isBlank()) return LlmResponse.Failure(LlmResult.Failure.NoKey())
        return try {
            val body = buildChatBody(messages, tools)
            val resp = httpPost(body)
            when {
                resp.status == 0 -> LlmResponse.Failure(LlmResult.Failure.Network(resp.error ?: "connect failed"))
                resp.status == 401 -> LlmResponse.Failure(LlmResult.Failure.BadKey("API key rejected (401)"))
                resp.status == 429 -> LlmResponse.Failure(
                    LlmResult.Failure.RateLimit(resp.body.providerErrorMessage() ?: "rate-limited (429)"),
                )
                resp.status !in 200..299 -> LlmResponse.Failure(
                    LlmResult.Failure.Other(resp.body.providerErrorMessage() ?: "HTTP ${resp.status}"),
                )
                resp.body.isEmpty() -> LlmResponse.Failure(LlmResult.Failure.Network("empty response body"))
                else -> parseChatSuccess(resp.body)
            }
        } catch (e: Exception) {
            LlmResponse.Failure(LlmResult.Failure.Network(e.message ?: "unknown error"))
        }
    }

    // ── chat: request serialisation ─────────────────────────────────────────

    /**
     * Build the Anthropic Messages request body. Pulls the (at most
     * one) [LlmMessage.System] out as the top-level `system` field —
     * Anthropic does NOT accept system inside `messages[]` like
     * OpenAI does.
     */
    private fun buildChatBody(messages: List<LlmMessage>, tools: List<LlmTool>): String {
        val systemPrompt = messages.filterIsInstance<LlmMessage.System>()
            .joinToString("\n\n") { it.text }
            .ifBlank { null }
        val nonSystem = messages.filterNot { it is LlmMessage.System }
        val obj = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (systemPrompt != null) put("system", systemPrompt)
            if (tools.isNotEmpty() || webSearchEnabled) put("tools", anthropicTools(tools))
            put("messages", anthropicMessages(nonSystem))
        }
        return obj.toString()
    }

    private fun anthropicTools(tools: List<LlmTool>) = buildJsonArray {
        if (webSearchEnabled) {
            add(buildJsonObject {
                put("type", WEB_SEARCH_TOOL_VERSION)
                put("name", "web_search")
            })
        }
        tools.forEach { tool ->
            add(buildJsonObject {
                put("name", tool.wireName)
                tool.description?.let { put("description", it) }
                put("input_schema", tool.inputSchema ?: EMPTY_OBJECT_SCHEMA)
            })
        }
    }

    private fun anthropicMessages(messages: List<LlmMessage>) = buildJsonArray {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m) {
                is LlmMessage.System -> error("system should be hoisted out before serialisation")
                is LlmMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("content", m.text)
                })
                is LlmMessage.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        m.text?.takeIf { it.isNotBlank() }?.let { text ->
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        m.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.wireName)
                                put("input", buildJsonObject {
                                    call.args.forEach { (k, v) -> put(k, v) }
                                })
                            })
                        }
                    })
                })
                is LlmMessage.Tool -> {
                    val toolBatch = mutableListOf<LlmMessage.Tool>()
                    while (i < messages.size && messages[i] is LlmMessage.Tool) {
                        toolBatch.add(messages[i] as LlmMessage.Tool)
                        i++
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            toolBatch.forEach { t ->
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", t.toolCallId)
                                    put("content", t.content)
                                    if (t.isError) put("is_error", true)
                                })
                            }
                        })
                    })
                    continue
                }
            }
            i++
        }
    }

    // ── chat: response parsing ──────────────────────────────────────────────

    private fun parseChatSuccess(payload: String): LlmResponse {
        val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("malformed JSON"))
        val content = json["content"] as? JsonArray
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("no content in response"))

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        for (block in content) {
            val obj = block as? JsonObject ?: continue
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { textBuilder.append(it) }
                "tool_use" -> {
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val input = (obj["input"] as? JsonObject)?.toMap() ?: emptyMap()
                    toolCalls.add(LlmToolCall(id = id, wireName = name, args = input))
                }
            }
        }
        val text = textBuilder.toString().trim().ifBlank { null }
        return LlmResponse.Ok(text = text, toolCalls = toolCalls)
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun statusToCompleteResult(status: Int, payload: String, error: String?): LlmResult = when {
        status == 0 -> LlmResult.Failure.Network(error ?: "connect failed")
        status == 401 -> LlmResult.Failure.BadKey("API key rejected (401)")
        status == 429 -> LlmResult.Failure.RateLimit(payload.providerErrorMessage() ?: "rate-limited (429)")
        status !in 200..299 -> LlmResult.Failure.Other(payload.providerErrorMessage() ?: "HTTP $status")
        payload.isEmpty() -> LlmResult.Failure.Network("empty response body")
        else -> parseCompleteSuccess(payload)
    }

    private fun parseCompleteSuccess(payload: String): LlmResult {
        val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return LlmResult.Failure.Network("malformed JSON")
        val content = json["content"] as? JsonArray
            ?: return LlmResult.Failure.Network("no content in response")
        val text = buildString {
            for (block in content) {
                val obj = block as? JsonObject ?: continue
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { append(it) }
                }
            }
        }.trim()
        return if (text.isEmpty()) LlmResult.Failure.Other("model returned empty text")
        else LlmResult.Success(text)
    }

    private suspend fun httpPost(body: String) = http.request(
        HttpRequest(
            method = "POST",
            url = ENDPOINT,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "User-Agent" to USER_AGENT,
            ),
            body = body.encodeToByteArray(),
            // Cap under the watch's PROCESSOR_TIMEOUT_MS (15 s) so a slow
            // LLM round produces a real failure message in time for the
            // watch to render it, instead of leaving the watch silent
            // past its own ceiling.
            timeoutMs = READ_TIMEOUT_MS,
        ),
    )

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val USER_AGENT = "Wristotle/companion"
        const val DEFAULT_MAX_TOKENS = 1024
        private const val READ_TIMEOUT_MS = 14_000

        /**
         * Anthropic's server-side `web_search` tool version string —
         * `web_search_<YYYYMMDD>`. Bump when Anthropic ships a newer
         * version; older types stay supported but new model features
         * ride on the latest pin.
         */
        private const val WEB_SEARCH_TOOL_VERSION = "web_search_20260209"

        /** Anthropic rejects tool definitions whose input_schema is missing;
         *  a parameterless tool needs an explicit empty-object schema. */
        private val EMPTY_OBJECT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
    }
}
