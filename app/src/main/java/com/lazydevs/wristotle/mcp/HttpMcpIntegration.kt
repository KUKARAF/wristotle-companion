package com.lazydevs.wristotle.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * HTTP MCP integration. Wraps the official kotlin-sdk client over a
 * Ktor OkHttp engine so we use the same network stack as the rest of
 * the app's outbound HTTP (weather, model downloads).
 *
 * Transport choice is dictated by [streamable]:
 *   - `true`  → Streaming HTTP (the current MCP spec recommendation).
 *   - `false` → SSE (legacy; keep because many MCP servers in the wild
 *               still only speak SSE).
 *
 * [authHeader] is the raw `Authorization` header value (e.g.
 * `"Bearer sk-..."`). Null when the server takes no auth. Stored on the
 * client's [defaultRequest] so every transport request carries it.
 *
 * Tool-list cache: 30 s, matching the mobileapp reference. Cleared by
 * [resetCache]. The cache duration is a server-courtesy default — most
 * servers don't change their tool list between requests; a UI refresh
 * tap calls [resetCache] explicitly when the user wants a fresh fetch.
 *
 * NOT thread-safe across processes; safe across coroutines on a single
 * instance because every SDK call is sequenced through the SDK's own
 * internal locking and the [connectMutex] guards the connect-once
 * lifecycle.
 */
class HttpMcpIntegration(
    override val name: String,
    private val url: String,
    private val streamable: Boolean = true,
    authHeader: String? = null,
    private val clientImplementation: Implementation =
        Implementation(name = "wristotle-companion", version = "1"),
) : McpIntegration {

    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(SSE)
        install(ContentNegotiation) { json() }
        if (authHeader != null) {
            defaultRequest { header("Authorization", authHeader) }
        }
    }

    private val client = Client(clientImplementation)

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

    override suspend fun close() {
        connectMutex.withLock {
            if (!connected) return
            try {
                client.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close failed for $name", t)
            }
            try {
                httpClient.close()
            } catch (t: Throwable) {
                Log.w(TAG, "ktor close failed for $name", t)
            }
            connected = false
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
            // Phase A doesn't paginate. Most server tool lists fit in the
            // default page; revisit if a server complains.
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
