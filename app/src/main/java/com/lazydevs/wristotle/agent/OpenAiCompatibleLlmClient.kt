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
 * OpenAI Chat Completions wire-shape client. The same protocol is
 * spoken by:
 *
 *   - OpenAI itself (`https://api.openai.com/v1/chat/completions`)
 *   - OpenRouter (`https://openrouter.ai/api/v1/chat/completions`)
 *   - Local Ollama (`http://localhost:11434/v1/chat/completions`)
 *   - llama.cpp server, vLLM, LiteLLM, …
 *
 * One client, many endpoints — [endpointUrl] is the full URL of the
 * `/chat/completions` route, supplied by the user in Settings. Auth is
 * sent as `Authorization: Bearer <apiKey>` if present, omitted when
 * blank (local self-hosted servers usually need no key).
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
                    messages.put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt),
                    )
                }
                messages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userQuery),
                )

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", maxTokens)
                }

                val (status, payload) = httpPost(endpointUrl, body.toString())
                    ?: return@withContext LlmResult.Failure.Network("connect failed")

                when {
                    status == 401 -> LlmResult.Failure.BadKey("API key rejected (401)")
                    status == 429 -> LlmResult.Failure.RateLimit(payload.providerErrorMessage() ?: "rate-limited (429)")
                    status !in 200..299 -> LlmResult.Failure.Other(
                        payload.providerErrorMessage() ?: "HTTP $status",
                    )
                    payload == null -> LlmResult.Failure.Network("empty response body")
                    else -> parseSuccess(payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "openai-compat call failed", e)
                LlmResult.Failure.Network(e.message ?: "unknown error")
            }
        }

    private fun parseSuccess(payload: String): LlmResult {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return LlmResult.Failure.Network("malformed JSON")
        val choices = json.optJSONArray("choices") ?: return LlmResult.Failure.Network("no choices")
        if (choices.length() == 0) return LlmResult.Failure.Other("model returned no choices")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return LlmResult.Failure.Network("missing choices[0].message")
        val text = message.optString("content").trim()
        return if (text.isEmpty()) {
            LlmResult.Failure.Other("model returned empty content")
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
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
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

    companion object {
        private const val TAG = "OpenAiCompatibleLlmClient"
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_ENDPOINT_URL = "https://api.openai.com/v1/chat/completions"
    }
}
