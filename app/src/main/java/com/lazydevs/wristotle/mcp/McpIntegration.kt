package com.lazydevs.wristotle.mcp

import kotlinx.serialization.json.JsonElement

/**
 * Single MCP transport — one remote server, or one in-process
 * built-in tool registry.
 *
 * Phase A ships [HttpMcpIntegration] only. A future
 * `BuiltInMcpIntegration` (exposing Wristotle's own intents to external
 * MCP clients) is sketched in `tasks.md` § Future work but not
 * implemented here. Either way the agent loop and settings layer talk
 * to this interface, not to the concrete classes.
 *
 * Methods are suspending because every implementation is I/O-bound on
 * the wire (HTTP) or on coroutine plumbing (built-in tools run on a
 * dispatcher). Callers should treat [connect] / [close] as bracketed —
 * one [connect] per session, one [close] when done.
 */
interface McpIntegration {
    /** User-visible label, also used as the routing key in [McpSession]. */
    val name: String

    /**
     * Open the underlying transport. Idempotent within a single
     * integration instance — calling twice is a no-op. Throws if the
     * server is unreachable or refuses the handshake.
     */
    suspend fun connect()

    /** Tear down the transport. Idempotent. */
    suspend fun close()

    /**
     * Fetch the server's tool list. Implementations may cache to avoid
     * a network round-trip on every UI refresh; [resetCache] forces a
     * refetch.
     */
    suspend fun listTools(): List<McpTool>

    /** Invalidate any tool-list cache so the next [listTools] refetches. */
    suspend fun resetCache()

    /**
     * Call a tool by name with a JSON argument object. The object's
     * shape must match the tool's `inputSchema` — phase A trusts the
     * caller to construct valid input; the agent loop in phase B will
     * be responsible for matching the schema.
     */
    suspend fun callTool(toolName: String, json: Map<String, JsonElement>): ToolCallResult
}
