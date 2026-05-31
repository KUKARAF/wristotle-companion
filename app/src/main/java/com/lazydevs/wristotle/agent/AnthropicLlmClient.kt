package com.lazydevs.wristotle.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anthropic Messages API client.
 *
 * Wire shape: POST `https://api.anthropic.com/v1/messages`,
 *   headers: `x-api-key`, `anthropic-version: 2023-06-01`,
 *   body: `{ model, max_tokens, system?, messages: [{role, content}] }`.
 *
 * Response: `{ content: [{type: "text", text: "..."}, ...], stop_reason, ... }`
 *
 * Concatenates all `text` blocks if the model returned multiple. No
 * streaming — B1 uses one POST, one body parse. Streaming + tool calls
 * arrive in B2 alongside the MCP agent loop.
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
                val (status, payload) = httpPost(ENDPOINT, body.toString())
                    ?: return@withContext LlmResult.Failure.Network("connect failed")

                when {
                    status == 401 -> LlmResult.Failure.BadKey("API key rejected (401)")
                    status == 429 -> LlmResult.Failure.RateLimit(payload.errorMessage() ?: "rate-limited (429)")
                    status !in 200..299 -> LlmResult.Failure.Other(
                        payload.errorMessage() ?: "HTTP $status",
                    )
                    payload == null -> LlmResult.Failure.Network("empty response body")
                    else -> parseSuccess(payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "anthropic call failed", e)
                LlmResult.Failure.Network(e.message ?: "unknown error")
            }
        }

    private fun parseSuccess(payload: String): LlmResult {
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
        return if (text.isEmpty()) {
            LlmResult.Failure.Other("model returned empty text")
        } else {
            LlmResult.Success(text)
        }
    }

    private fun httpPost(url: String, body: String): Pair<Int, String?>? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            setRequestProperty("User-Agent", "Wristotle/companion")
        }
        return try {
            conn.outputStream.bufferedWriter().use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() }
            code to resp
        } catch (e: IOException) {
            Log.w(TAG, "POST $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Best-effort extraction of `.error.message` from Anthropic's error body. */
    private fun String?.errorMessage(): String? {
        if (this == null) return null
        return runCatching {
            JSONObject(this).optJSONObject("error")?.optString("message")?.ifBlank { null }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AnthropicLlmClient"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 1024
    }
}
