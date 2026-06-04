package com.lazydevs.wristotle.agent

import android.util.Log
import com.lazydevs.wristotle.speech.util.SimpleHttp
import com.lazydevs.wristotle.speech.util.providerErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject

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
 */
class AnthropicLlmClient(
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : LlmClient {

    override suspend fun complete(userQuery: String, systemPrompt: String?): LlmResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext LlmResult.Failure.NoKey()
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", maxTokens)
                    if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", userQuery),
                        ),
                    )
                }
                val (status, payload) = httpPost(body.toString())
                    ?: return@withContext LlmResult.Failure.Network("connect failed")
                statusToCompleteResult(status, payload)
            } catch (e: Exception) {
                Log.w(TAG, "anthropic complete failed", e)
                LlmResult.Failure.Network(e.message ?: "unknown error")
            }
        }

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<LlmTool>,
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext LlmResponse.Failure(LlmResult.Failure.NoKey())
        try {
            val body = buildChatBody(messages, tools)
            val (status, payload) = httpPost(body)
                ?: return@withContext LlmResponse.Failure(LlmResult.Failure.Network("connect failed"))
            when {
                status == 401 -> LlmResponse.Failure(LlmResult.Failure.BadKey("API key rejected (401)"))
                status == 429 -> LlmResponse.Failure(
                    LlmResult.Failure.RateLimit(payload.providerErrorMessage() ?: "rate-limited (429)"),
                )
                status !in 200..299 -> LlmResponse.Failure(
                    LlmResult.Failure.Other(payload.providerErrorMessage() ?: "HTTP $status"),
                )
                payload == null -> LlmResponse.Failure(LlmResult.Failure.Network("empty response body"))
                else -> parseChatSuccess(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "anthropic chat failed", e)
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
            if (tools.isNotEmpty()) put("tools", anthropicTools(tools))
            put("messages", anthropicMessages(nonSystem))
        }
        return obj.toString()
    }

    private fun anthropicTools(tools: List<LlmTool>) = kotlinx.serialization.json.buildJsonArray {
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("name", tool.wireName)
                    tool.description?.let { put("description", it) }
                    put("input_schema", tool.inputSchema ?: EMPTY_OBJECT_SCHEMA)
                },
            )
        }
    }

    private fun anthropicMessages(messages: List<LlmMessage>) = kotlinx.serialization.json.buildJsonArray {
        // Adjacent Tool-results are coalesced into a single user message
        // with multiple tool_result blocks (Anthropic prefers this shape
        // and rejects consecutive user-role messages otherwise).
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m) {
                is LlmMessage.System -> error("system should be hoisted out before serialisation")
                is LlmMessage.User -> add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", m.text)
                    },
                )
                is LlmMessage.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", kotlinx.serialization.json.buildJsonArray {
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
                    // Coalesce consecutive Tool messages.
                    val toolBatch = mutableListOf<LlmMessage.Tool>()
                    while (i < messages.size && messages[i] is LlmMessage.Tool) {
                        toolBatch.add(messages[i] as LlmMessage.Tool)
                        i++
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", kotlinx.serialization.json.buildJsonArray {
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
        val content = json["content"] as? kotlinx.serialization.json.JsonArray
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("no content in response"))

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        for (block in content) {
            val obj = block as? JsonObject ?: continue
            when (obj["type"]?.toString()?.trim('"')) {
                "text" -> obj["text"]?.toString()?.trim('"')?.let { textBuilder.append(it) }
                "tool_use" -> {
                    val id = obj["id"]?.toString()?.trim('"') ?: continue
                    val name = obj["name"]?.toString()?.trim('"') ?: continue
                    val input = (obj["input"] as? JsonObject)?.toMap() ?: emptyMap()
                    toolCalls.add(LlmToolCall(id = id, wireName = name, args = input))
                }
            }
        }
        val text = textBuilder.toString().trim().ifBlank { null }
        return LlmResponse.Ok(text = text, toolCalls = toolCalls)
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun statusToCompleteResult(status: Int, payload: String?): LlmResult = when {
        status == 401 -> LlmResult.Failure.BadKey("API key rejected (401)")
        status == 429 -> LlmResult.Failure.RateLimit(payload.providerErrorMessage() ?: "rate-limited (429)")
        status !in 200..299 -> LlmResult.Failure.Other(payload.providerErrorMessage() ?: "HTTP $status")
        payload == null -> LlmResult.Failure.Network("empty response body")
        else -> parseCompleteSuccess(payload)
    }

    private fun parseCompleteSuccess(payload: String): LlmResult {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return LlmResult.Failure.Network("malformed JSON")
        val content = json.optJSONArray("content")
            ?: return LlmResult.Failure.Network("no content in response")
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    append(block.optString("text"))
                }
            }
        }.trim()
        return if (text.isEmpty()) LlmResult.Failure.Other("model returned empty text")
        else LlmResult.Success(text)
    }

    private fun httpPost(body: String): Pair<Int, String?>? = SimpleHttp.request(
        url = ENDPOINT,
        method = "POST",
        headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "x-api-key" to apiKey,
            "anthropic-version" to ANTHROPIC_VERSION,
        ),
        body = body,
        // Cap under the watch's PROCESSOR_TIMEOUT_MS (15 s in
        // input/processor.c) so a slow LLM round produces a real failure
        // message in time for the watch to render it, instead of leaving
        // the watch silent past its own ceiling. The AskAgent loop also
        // emits agent_status on Status.Thinking which re-arms that timer.
        readTimeoutMs = READ_TIMEOUT_MS,
    )

    companion object {
        private const val TAG = "AnthropicLlmClient"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 1024
        private const val READ_TIMEOUT_MS = 14_000

        /** Anthropic rejects tool definitions whose input_schema is missing;
         *  a parameterless tool needs an explicit empty-object schema. */
        private val EMPTY_OBJECT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
    }
}
