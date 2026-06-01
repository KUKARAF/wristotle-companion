package com.lazydevs.wristotle.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One message in an LLM chat exchange. Covers the four shapes both
 * Anthropic and OpenAI-compatible providers need to round-trip a tool-
 * calling agent loop: System (instructions), User (transcript), Assistant
 * (model output — may include tool calls), Tool (result the LLM
 * requested).
 *
 * Provider-agnostic — each [LlmClient] impl serialises these to its
 * own wire shape (Anthropic's `messages[].content` blocks vs OpenAI's
 * flat `messages[]` array with `role: "tool"`).
 */
sealed interface LlmMessage {
    data class System(val text: String) : LlmMessage
    data class User(val text: String) : LlmMessage

    /**
     * Assistant turn. `text` is optional because some turns are
     * pure-tool-call (no commentary). `toolCalls` is empty for a
     * final text-only response.
     */
    data class Assistant(
        val text: String?,
        val toolCalls: List<LlmToolCall> = emptyList(),
    ) : LlmMessage

    /**
     * Result of executing one tool the assistant requested.
     * [toolCallId] threads back to the [LlmToolCall.id] so the
     * provider can match request ↔ response across rounds.
     * [isError] = true when the MCP server reported failure; the
     * LLM uses it to decide whether to retry or give up.
     */
    data class Tool(
        val toolCallId: String,
        val name: String,
        val content: String,
        val isError: Boolean,
    ) : LlmMessage
}

/**
 * Tool definition the LLM sees in its `tools` parameter. Built from
 * an [com.lazydevs.wristotle.mcp.McpTool] and prefixed with the
 * source integration's name so multiple servers can expose
 * same-named tools without colliding (`<integration>.<tool>` shape,
 * dot-separated — both Anthropic and OpenAI accept `[a-zA-Z0-9_-]`
 * tool names, so we use dash-separation on the wire and split on
 * dispatch).
 */
data class LlmTool(
    val integrationName: String,
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?,
) {
    /** Wire-safe tool name: `<integration>__<tool>`. Double-underscore
     *  matches the mobileapp reference; survives both providers'
     *  identifier constraints and reverses unambiguously. */
    val wireName: String get() = "${integrationName}__$name"

    companion object {
        /** Inverse of [wireName]: split a wire tool name back into
         *  (integration, tool). Returns null when the name doesn't
         *  carry the prefix (LLM hallucination). */
        fun parseWireName(wire: String): Pair<String, String>? {
            val idx = wire.indexOf("__")
            if (idx <= 0 || idx >= wire.length - 2) return null
            return wire.substring(0, idx) to wire.substring(idx + 2)
        }
    }
}

/**
 * One tool invocation the assistant requested. [id] is provider-
 * supplied (Anthropic: `tool_use.id`, OpenAI: `tool_calls[].id`) and
 * round-trips back as [LlmMessage.Tool.toolCallId].
 */
data class LlmToolCall(
    val id: String,
    val wireName: String,
    val args: Map<String, JsonElement>,
)
