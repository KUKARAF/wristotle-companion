// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.agent

import android.util.Log
import com.lazydevs.wristotle.mcp.HttpMcpIntegration
import com.lazydevs.wristotle.mcp.McpServerEntity
import com.lazydevs.wristotle.mcp.McpSession
import com.lazydevs.wristotle.speech.nlu.mcp.ToolCallResult
import com.lazydevs.wristotle.speech.nlu.mcp.McpTool
import com.lazydevs.wristotle.speech.nlu.mcp.McpIntegration

/**
 * Drives the LLM ↔ MCP-tools loop for one AskAgent voice query.
 *
 * Per invocation:
 *  1. Open an [McpSession] over the given enabled servers; list every
 *     server's tools.
 *  2. Send the user query (with any system prompt) + tool list to the
 *     LLM via [LlmClient.chat].
 *  3. If the response carries no tool calls, return its text — done.
 *  4. Otherwise execute each tool call in order, feed the results back
 *     as [LlmMessage.Tool] messages, and loop. Stop at
 *     [MAX_ITERATIONS] regardless.
 *  5. Close the session (and every integration's Ktor engine) before
 *     returning.
 *
 * Status callbacks fire on every meaningful state change so the
 * caller can route them to the watch (e.g. "Calling github.get_me…").
 * The loop itself doesn't know about the wire to the watch — that's
 * the handler's job.
 *
 * Phase B2 opens a fresh session per query (open + close around the
 * loop). The McpServersViewModel's per-server cache is independent —
 * settings-card probes don't share state with the agent loop. A
 * future B3 may unify the two via an Application-scoped cache.
 */
class AgentLoop(
    private val llm: LlmClient,
    private val maxIterations: Int = MAX_ITERATIONS,
) {

    /** Per-round signal for callers that want to surface progress. */
    sealed interface Status {
        /** About to call the LLM (round number is 1-based). */
        data class Thinking(val round: Int) : Status
        /** Executing a tool call. */
        data class CallingTool(val toolName: String) : Status
        /** Tool finished — content is short summary for display. */
        data class ToolDone(val toolName: String, val ok: Boolean) : Status
    }

    sealed interface Outcome {
        data class Done(val text: String) : Outcome
        data class HitMaxIterations(val partialText: String?) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Run one agent-loop turn. [servers] are the user's enabled MCP
     * servers (already filtered by `enabled = 1`); pass empty when no
     * MCP servers are configured — the caller should use
     * [LlmClient.complete] in that case rather than calling this with
     * an empty list.
     */
    suspend fun run(
        userQuery: String,
        systemPrompt: String?,
        servers: List<McpServerEntity>,
        onStatus: suspend (Status) -> Unit = {},
    ): Outcome {
        val integrations = servers.map { server ->
            HttpMcpIntegration(
                name = server.name,
                url = server.url,
                streamable = server.streamable,
                authHeader = server.authHeader,
            )
        }
        val session = McpSession(integrations)
        return try {
            session.openSession()
            val tools = listAllTools(session, servers)
            if (tools.isEmpty()) {
                Log.w(TAG, "no tools across ${servers.size} servers; falling back to text-only")
            }
            runLoop(userQuery, systemPrompt, session, tools, onStatus)
        } finally {
            runCatching { session.closeSession() }
        }
    }

    private suspend fun listAllTools(
        session: McpSession,
        servers: List<McpServerEntity>,
    ): List<LlmTool> = session.listTools().mapNotNull { mcpTool ->
        // McpTool.integrationName matches the integration's name (== server.name)
        val server = servers.firstOrNull { it.name == mcpTool.integrationName } ?: return@mapNotNull null
        LlmTool(
            integrationName = server.name,
            name = mcpTool.name,
            description = mcpTool.description,
            inputSchema = mcpTool.inputSchema,
        )
    }

    private suspend fun runLoop(
        userQuery: String,
        systemPrompt: String?,
        session: McpSession,
        tools: List<LlmTool>,
        onStatus: suspend (Status) -> Unit,
    ): Outcome {
        val messages = mutableListOf<LlmMessage>()
        if (!systemPrompt.isNullOrBlank()) messages.add(LlmMessage.System(systemPrompt))
        messages.add(LlmMessage.User(userQuery))

        var lastAssistantText: String? = null
        for (round in 1..maxIterations) {
            onStatus(Status.Thinking(round))
            val response = llm.chat(messages, tools)
            val ok = when (response) {
                is LlmResponse.Ok -> response
                is LlmResponse.Failure -> return Outcome.Failed(response.failure.message)
            }
            lastAssistantText = ok.text ?: lastAssistantText

            if (ok.toolCalls.isEmpty()) {
                // Final text-only turn — done.
                return Outcome.Done(ok.text ?: "(no answer)")
            }

            messages.add(LlmMessage.Assistant(text = ok.text, toolCalls = ok.toolCalls))

            for (call in ok.toolCalls) {
                val (integrationName, toolName) = LlmTool.parseWireName(call.wireName)
                    ?: run {
                        onStatus(Status.ToolDone(call.wireName, ok = false))
                        messages.add(
                            LlmMessage.Tool(
                                toolCallId = call.id,
                                name = call.wireName,
                                content = "unknown tool: ${call.wireName}",
                                isError = true,
                            ),
                        )
                        continue
                    }
                onStatus(Status.CallingTool(call.wireName))
                val result = session.callTool(integrationName, toolName, call.args)
                val (content, isError) = when (result) {
                    is ToolCallResult.Success -> result.text to false
                    is ToolCallResult.Failure -> result.message to true
                }
                onStatus(Status.ToolDone(call.wireName, ok = !isError))
                messages.add(
                    LlmMessage.Tool(
                        toolCallId = call.id,
                        name = call.wireName,
                        content = content,
                        isError = isError,
                    ),
                )
            }
        }
        return Outcome.HitMaxIterations(lastAssistantText)
    }

    companion object {
        private const val TAG = "AgentLoop"
        /** Bounds runaway loops; matches the mobileapp + Wrist AI references. */
        const val MAX_ITERATIONS = 5
    }
}