package com.lazydevs.wristotle.mcp

/**
 * Outcome of a single MCP tool call.
 *
 * Phase A flattens the SDK's structured result into a string —
 * `TextContent` parts joined by newlines for the success case,
 * server-reported error text (or thrown exception message) for the
 * failure case. Phase B (AskAgent) may want the structured JSON back
 * for richer LLM context; at that point introduce a structured variant
 * here rather than parsing the string apart in the agent loop.
 */
sealed interface ToolCallResult {
    data class Success(val text: String) : ToolCallResult
    data class Failure(val message: String) : ToolCallResult
}
