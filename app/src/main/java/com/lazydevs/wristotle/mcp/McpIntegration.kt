package com.lazydevs.wristotle.mcp

import kotlinx.serialization.json.JsonElement

/**
 * Single MCP transport. Suspending throughout because every
 * implementation is I/O-bound on the wire.
 *
 * Lifecycle is bracketed — one [connect] per session, one [close] when
 * done. [listTools] may serve a cached value; [resetCache] forces a
 * refetch.
 */
interface McpIntegration {
    val name: String

    suspend fun connect()
    suspend fun close()

    suspend fun listTools(): List<McpTool>
    suspend fun resetCache()

    /**
     * The `json` arg shape must match the tool's `inputSchema` — the
     * caller is responsible for constructing valid input. (Phase B2's
     * agent loop is the only programmatic caller; phase A's debug card
     * takes raw user JSON.)
     */
    suspend fun callTool(toolName: String, json: Map<String, JsonElement>): ToolCallResult
}
