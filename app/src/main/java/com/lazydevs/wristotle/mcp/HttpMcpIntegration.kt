// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.mcp

import android.util.Log
import com.lazydevs.wristotle.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.lazydevs.wristotle.speech.nlu.mcp.McpIntegration
import com.lazydevs.wristotle.speech.nlu.mcp.McpTool
import com.lazydevs.wristotle.speech.nlu.mcp.ToolCallResult

/**
 * HTTP MCP integration over the official kotlin-sdk client + Ktor
 * OkHttp engine.
 *
 * - `streamable = true` → Streaming HTTP (current MCP spec
 *   recommendation); `false` → SSE (legacy, kept because many servers
 *   in the wild still only speak it).
 * - `authHeader` is the raw `Authorization` value (e.g. `"Bearer
 *   sk-..."`), installed once on Ktor's default request so every
 *   transport request carries it.
 * - Tool-list cache: 30 s TTL keyed on the instance — most servers
 *   don't change their tool list between requests. A user-driven
 *   refresh tap should call [resetCache] before [listTools] to force a
 *   fresh fetch (the dialog refresh button does this).
 *
 * Safe across coroutines on a single instance: the SDK has its own
 * locking, and [connectMutex] guards the connect-once / close-once
 * lifecycle.
 */
class HttpMcpIntegration(
    override val name: String,
    private val url: String,
    private val streamable: Boolean = true,
    authHeader: String? = null,
) : McpIntegration {

    // ContentNegotiation is intentionally NOT installed — the MCP SDK
    // sets Content-Type and serialises JSON itself. Installing
    // ContentNegotiation { json() } makes Ktor APPEND its own
    // `Accept: application/json` on every request, which collides with
    // the SDK's `Accept: application/json, text/event-stream` line and
    // serialises as the malformed `application/json, text/event-stream;
    // application/json` that strict MCP servers reject before the auth
    // check.
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(SSE)
        if (BuildConfig.DEBUG) {
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        // Authorization header (and any other secret-looking headers)
                        // gets redacted below so it doesn't leak into bug reports
                        // pasted out of logcat.
                        val redacted = message.lineSequence()
                            .joinToString("\n") { line ->
                                when {
                                    line.startsWith("Authorization:", ignoreCase = true) ||
                                        line.startsWith("-> Authorization:", ignoreCase = true) ->
                                        line.substringBefore(":") + ": <redacted>"
                                    else -> line
                                }
                            }
                        Log.d(TAG, redacted)
                    }
                }
                level = LogLevel.HEADERS
            }
        }
        if (authHeader != null) {
            defaultRequest { header("Authorization", authHeader) }
        }
    }

    private val client = Client(Implementation(name = "wristotle-companion", version = "1"))

    private val transport = if (streamable) {
        StreamableHttpClientTransport(httpClient, url)
    } else {
        SseClientTransport(httpClient, url)
    }

    private val connectMutex = Mutex()
    @Volatile private var connected = false

    @Volatile private var cachedTools: List<McpTool>? = null
    @Volatile private var cacheTimestampMs: Long = 0L

    override suspend fun connect() {
        connectMutex.withLock {
            if (connected) return
            client.connect(transport)
            connected = true
            Log.d(TAG, "connected to $name @ $url")
        }
    }

    // MUST close BOTH client (SDK) AND httpClient (Ktor engine) — the SDK
    // doesn't own the engine, so closing only the SDK leaks the OkHttp
    // connection pool until GC. httpClient is created at construction
    // time, so close() must always tear it down even when connect() never
    // succeeded — otherwise a failed connect leaks the engine until GC.
    override suspend fun close() {
        connectMutex.withLock {
            if (connected) {
                runCatching { client.close() }.onFailure { Log.w(TAG, "sdk close failed for $name", it) }
                connected = false
            }
            runCatching { httpClient.close() }.onFailure { Log.w(TAG, "ktor close failed for $name", it) }
        }
    }

    override suspend fun resetCache() {
        cachedTools = null
    }

    override suspend fun listTools(): List<McpTool> {
        val now = System.currentTimeMillis()
        val cached = cachedTools
        if (cached != null && now - cacheTimestampMs < CACHE_TTL_MS) return cached

        val result = client.listTools()
        if (result?.nextCursor != null) {
            // First page only — most servers fit. Revisit if one complains.
            Log.w(TAG, "$name returned a paginated tool list; only first page is shown")
        }
        val tools = (result?.tools ?: emptyList()).map { sdkTool ->
            McpTool(
                integrationName = name,
                name = sdkTool.name,
                description = sdkTool.description,
                inputSchema = sdkTool.inputSchema as? JsonObject,
            )
        }
        cachedTools = tools
        cacheTimestampMs = now
        return tools
    }

    override suspend fun callTool(
        toolName: String,
        json: Map<String, JsonElement>,
    ): ToolCallResult {
        return try {
            val result = client.callTool(toolName, json)
                ?: return ToolCallResult.Failure("no response from server")
            val text = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text ?: "" }
                .ifBlank { "(empty result)" }
            if (result.isError == true) ToolCallResult.Failure(text) else ToolCallResult.Success(text)
        } catch (t: Throwable) {
            Log.w(TAG, "callTool $name/$toolName failed", t)
            ToolCallResult.Failure(t.message ?: t::class.java.simpleName)
        }
    }

    companion object {
        private const val TAG = "HttpMcpIntegration"
        private const val CACHE_TTL_MS = 30_000L
    }
}