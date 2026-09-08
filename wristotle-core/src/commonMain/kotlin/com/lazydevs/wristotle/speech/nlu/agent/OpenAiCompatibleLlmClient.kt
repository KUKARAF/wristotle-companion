// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.agent

import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.http.HttpRequest
import com.lazydevs.wristotle.speech.nlu.http.providerErrorMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Chat Completions wire-shape client. Same protocol is spoken
 * by OpenAI proper, OpenRouter, local Ollama, llama.cpp server, vLLM,
 * LiteLLM — [endpointUrl] is user-supplied. Auth omitted when key is
 * blank so local self-hosted servers work.
 *
 * `chat()` uses native tool-calling: `tools[].function.parameters`
 * holds the JSON schema, response's `choices[0].message.tool_calls`
 * carries the invocations. Tool result messages use `role: "tool"`
 * with `tool_call_id` (distinct from Anthropic's structured-content
 * approach).
 *
 * R5 batch 2 — lifted from :app/agent. HTTP goes through the
 * [HttpClient] seam; response parsing is kotlinx.serialization.json
 * end-to-end.
 */
class OpenAiCompatibleLlmClient(
    private val http: HttpClient,
    private val endpointUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    /** Extra HTTP headers merged onto every request (after the built-ins, so a
     *  user header can override). Enables server-side session backends like
     *  Hermes' X-Hermes-Session-Id without special-casing any provider. */
    private val customHeaders: Map<String, String> = emptyMap(),
) : LlmClient {

    override suspend fun complete(
        userQuery: String,
        systemPrompt: String?,
        history: List<LlmMessage>,
    ): LlmResult {
        if (endpointUrl.isBlank()) return LlmResult.Failure.Other("no endpoint URL configured")
        return try {
            val msgs = buildList {
                if (!systemPrompt.isNullOrBlank()) add(LlmMessage.System(systemPrompt))
                addAll(history)
                add(LlmMessage.User(userQuery))
            }
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                put("messages", openAiMessages(msgs))
            }.toString()
            val resp = httpPost(body)
            statusToCompleteResult(resp.status, resp.body, resp.error)
        } catch (e: Exception) {
            LlmResult.Failure.Network(e.message ?: "unknown error")
        }
    }

    override suspend fun chat(messages: List<LlmMessage>, tools: List<LlmTool>): LlmResponse {
        if (endpointUrl.isBlank()) return LlmResponse.Failure(LlmResult.Failure.Other("no endpoint URL configured"))
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

    private fun buildChatBody(messages: List<LlmMessage>, tools: List<LlmTool>): String {
        val obj = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", openAiMessages(messages))
            if (tools.isNotEmpty()) put("tools", openAiTools(tools))
        }
        return obj.toString()
    }

    private fun openAiTools(tools: List<LlmTool>) = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.wireName)
                    tool.description?.let { put("description", it) }
                    put("parameters", tool.inputSchema ?: EMPTY_OBJECT_SCHEMA)
                })
            })
        }
    }

    private fun openAiMessages(messages: List<LlmMessage>) = buildJsonArray {
        messages.forEach { m ->
            when (m) {
                is LlmMessage.System -> add(buildJsonObject {
                    put("role", "system")
                    put("content", m.text)
                })
                is LlmMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("content", m.text)
                })
                is LlmMessage.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    // OpenAI requires content to be present (may be null
                    // when tool_calls are set). Empty string would be
                    // rejected by some servers.
                    if (m.text.isNullOrBlank()) {
                        put("content", JsonNull)
                    } else {
                        put("content", m.text)
                    }
                    if (m.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            m.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.wireName)
                                        // OpenAI wants `arguments` as a JSON-encoded STRING,
                                        // not a JSON object.
                                        put(
                                            "arguments",
                                            buildJsonObject {
                                                call.args.forEach { (k, v) -> put(k, v) }
                                            }.toString(),
                                        )
                                    })
                                })
                            }
                        })
                    }
                })
                is LlmMessage.Tool -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId)
                    put("content", m.content)
                })
            }
        }
    }

    // ── chat: response parsing ──────────────────────────────────────────────

    private fun parseChatSuccess(payload: String): LlmResponse {
        val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("malformed JSON"))
        val choices = json["choices"] as? JsonArray
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("no choices"))
        if (choices.isEmpty()) return LlmResponse.Failure(LlmResult.Failure.Other("model returned no choices"))
        val message = (choices[0] as? JsonObject)?.get("message") as? JsonObject
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("missing choices[0].message"))

        val text = (message["content"] as? JsonPrimitive)
            ?.contentOrNull?.trim()?.ifBlank { null }

        val toolCalls = mutableListOf<LlmToolCall>()
        (message["tool_calls"] as? JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val fn = obj["function"] as? JsonObject ?: return@forEach
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            // `arguments` is a JSON-encoded string per the OpenAI spec.
            val argsRaw = (fn["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}"
            val argsObj = runCatching { Json.parseToJsonElement(argsRaw).jsonObject }.getOrNull()
                ?: return@forEach
            toolCalls.add(LlmToolCall(id = id, wireName = name, args = argsObj.toMap()))
        }
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
        val choices = json["choices"] as? JsonArray ?: return LlmResult.Failure.Network("no choices")
        if (choices.isEmpty()) return LlmResult.Failure.Other("model returned no choices")
        val message = (choices[0] as? JsonObject)?.get("message") as? JsonObject
            ?: return LlmResult.Failure.Network("missing choices[0].message")
        val text = (message["content"] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""
        return if (text.isEmpty()) LlmResult.Failure.Other("model returned empty content")
        else LlmResult.Success(text)
    }

    private suspend fun httpPost(body: String) = http.request(
        HttpRequest(
            method = "POST",
            url = endpointUrl,
            headers = buildMap {
                put("Content-Type", "application/json")
                put("Accept", "application/json")
                put("User-Agent", USER_AGENT)
                // Local self-hosted servers (Ollama, llama.cpp) work without auth;
                // omit the header entirely when the key is blank.
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                putAll(customHeaders)
            },
            body = body.encodeToByteArray(),
            timeoutMs = readTimeoutMs,
        ),
    )

    companion object {
        private const val USER_AGENT = "Wristotle/companion"
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_ENDPOINT_URL = "https://api.openai.com/v1/chat/completions"
        /** Default LLM read timeout — the previous hardcoded value, kept so
         *  default behaviour is unchanged. User-overridable via AskAgentSettings
         *  (raise for slow local reasoning models); the watch is kept alive past
         *  its 15 s ceiling by AskAgentHandler's periodic agent_status heartbeat. */
        const val DEFAULT_READ_TIMEOUT_MS = 14_000

        private val EMPTY_OBJECT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
    }
}
