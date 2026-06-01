package com.lazydevs.wristotle.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
 */
class OpenAiCompatibleLlmClient(
    private val endpointUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : LlmClient {

    override suspend fun complete(userQuery: String, systemPrompt: String?): LlmResult =
        withContext(Dispatchers.IO) {
            if (endpointUrl.isBlank()) {
                return@withContext LlmResult.Failure.Other("no endpoint URL configured")
            }
            try {
                val messages = JSONArray()
                if (!systemPrompt.isNullOrBlank()) {
                    messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                messages.put(JSONObject().put("role", "user").put("content", userQuery))

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", maxTokens)
                }

                val (status, payload) = httpPost(body.toString())
                    ?: return@withContext LlmResult.Failure.Network("connect failed")
                statusToCompleteResult(status, payload)
            } catch (e: Exception) {
                Log.w(TAG, "openai-compat complete failed", e)
                LlmResult.Failure.Network(e.message ?: "unknown error")
            }
        }

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<LlmTool>,
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank()) {
            return@withContext LlmResponse.Failure(LlmResult.Failure.Other("no endpoint URL configured"))
        }
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
            Log.w(TAG, "openai-compat chat failed", e)
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

    private fun openAiTools(tools: List<LlmTool>) = kotlinx.serialization.json.buildJsonArray {
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

    private fun openAiMessages(messages: List<LlmMessage>) = kotlinx.serialization.json.buildJsonArray {
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
                        put("content", kotlinx.serialization.json.JsonNull)
                    } else {
                        put("content", m.text)
                    }
                    if (m.toolCalls.isNotEmpty()) {
                        put("tool_calls", kotlinx.serialization.json.buildJsonArray {
                            m.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.wireName)
                                        // OpenAI wants `arguments` as a JSON-encoded STRING,
                                        // not a JSON object. Both the loop and the wire
                                        // recover the structured form by re-parsing.
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
        val choices = json["choices"] as? kotlinx.serialization.json.JsonArray
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("no choices"))
        if (choices.isEmpty()) return LlmResponse.Failure(LlmResult.Failure.Other("model returned no choices"))
        val message = (choices[0] as? JsonObject)?.get("message") as? JsonObject
            ?: return LlmResponse.Failure(LlmResult.Failure.Network("missing choices[0].message"))

        val text = (message["content"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.trim()?.ifBlank { null }

        val toolCalls = mutableListOf<LlmToolCall>()
        (message["tool_calls"] as? kotlinx.serialization.json.JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val id = obj["id"]?.toString()?.trim('"') ?: return@forEach
            val fn = obj["function"] as? JsonObject ?: return@forEach
            val name = fn["name"]?.toString()?.trim('"') ?: return@forEach
            // `arguments` is a JSON-encoded string per the OpenAI spec.
            val argsRaw = (fn["arguments"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "{}"
            val argsObj = runCatching { Json.parseToJsonElement(argsRaw).jsonObject }.getOrNull()
                ?: return@forEach
            toolCalls.add(LlmToolCall(id = id, wireName = name, args = argsObj.toMap()))
        }
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
        val choices = json.optJSONArray("choices") ?: return LlmResult.Failure.Network("no choices")
        if (choices.length() == 0) return LlmResult.Failure.Other("model returned no choices")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return LlmResult.Failure.Network("missing choices[0].message")
        val text = message.optString("content").trim()
        return if (text.isEmpty()) LlmResult.Failure.Other("model returned empty content")
        else LlmResult.Success(text)
    }

    private fun httpPost(body: String): Pair<Int, String?>? {
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "Wristotle/companion")
        }
        return try {
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }
            code to resp
        } catch (e: IOException) {
            Log.w(TAG, "POST $endpointUrl failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatibleLlmClient"
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_ENDPOINT_URL = "https://api.openai.com/v1/chat/completions"

        private val EMPTY_OBJECT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
    }
}
