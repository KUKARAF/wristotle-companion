// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.transport.sendAgentStatus
import com.lazydevs.wristotle.speech.nlu.transport.sendResponse
import com.lazydevs.wristotle.logging.WristotleLogger
import com.lazydevs.wristotle.mcp.HttpMcpIntegration
import com.lazydevs.wristotle.mcp.McpServerRepository
import com.lazydevs.wristotle.speech.nlu.agent.AgentLoop
import com.lazydevs.wristotle.speech.nlu.agent.LlmResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.mcp.McpServerConfig
import com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.stringSlot
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Read-only by construction — not in the confirm-before-dispatch set.
 *
 * Two paths:
 *  - No enabled MCP servers → one-shot [LlmClient.complete] (B1
 *    behaviour).
 *  - Any enabled MCP server → [AgentLoop] over the chat API with the
 *    aggregated tool list, up to 5 rounds.
 *
 * In the agent-loop path, intermediate "🛠 <toolName>" status lines
 * are sent to the watch via [WatchTransport.sendResponse] so the
 * user sees what the LLM is doing across rounds. Each shows up as a
 * separate chat bubble — chatty but informative, matches the
 * Pebble-Wrist-AI reference pattern. The final answer returns via
 * this handler's [String] result so the dispatch pipeline handles
 * conversation history + the last watch bubble.
 *
 * Reads `settings.activeClient()` on every call so a settings edit
 * takes effect on the next voice query — no app restart.
 */
class AskAgentHandler(
    private val settings: AskAgentSettings,
    private val mcpServers: McpServerRepository,
    private val transport: WatchTransport,
) : ActionHandler {

    override val tag: String = "ask-agent"
    override val intent: Intent = Intent.AskAgent
    override val cardKind: String? = "agent_answer"

    override suspend fun handle(result: IntentResult): String {
        val query = result.stringSlot(SlotKeys.Query)
        if (query.isEmpty()) return NO_QUERY_HINT
        // System prompt is the user-visible value from Settings (default
        // is the watch-friendly baseline; user can edit or clear it via
        // the card's "Reset to default" button). No hidden prepend —
        // what you see in Settings is what gets sent.
        val systemPrompt = settings.systemPrompt.value.takeIf { it.isNotBlank() }
        val client = settings.activeClient()

        val enabled = mcpServers.listEnabled()
        // Both paths run inside a watch heartbeat (see withWatchHeartbeat) so a
        // slow model — a local reasoning model thinking for 30-60 s — doesn't
        // leave the watch silent past its 15 s ceiling.
        return withWatchHeartbeat {
            // The chat/tool path is needed when EITHER the user has MCP servers
            // OR the active provider has built-in server tools (today: Anthropic
            // web_search). Otherwise the cheaper one-shot complete() suffices.
            if (enabled.isEmpty() && !settings.activeProviderHasServerTools()) {
                renderComplete(client.complete(query, systemPrompt)).trimForWatch()
            } else {
                val loop = AgentLoop(
                    llm = client,
                    integrationFactory = { cfg ->
                        HttpMcpIntegration(
                            name = cfg.name,
                            url = cfg.url,
                            streamable = cfg.streamable,
                            authHeader = cfg.authHeader,
                        )
                    },
                    log = WristotleLogger,
                )
                val serverConfigs = enabled.map { entity ->
                    McpServerConfig(
                        name = entity.name,
                        url = entity.url,
                        streamable = entity.streamable,
                        authHeader = entity.authHeader,
                    )
                }
                val outcome = loop.run(
                    userQuery = query,
                    systemPrompt = systemPrompt,
                    servers = serverConfigs,
                    // Per-round watch status (B3): goes to the hint-bar slot via
                    // sendAgentStatus, NOT sendResponse (the chat surface renders
                    // one bubble per query — see feedback_watch_chat_single_bubble).
                    // withWatchHeartbeat re-arms the watch's 15 s timer even WITHIN
                    // a single long round; these add per-round/tool specificity.
                    onStatus = { status ->
                        val line = when (status) {
                            is AgentLoop.Status.Thinking ->
                                if (status.round == 1) "→ thinking…"
                                else "→ thinking (round ${status.round})…"
                            is AgentLoop.Status.CallingTool -> "→ ${friendly(status.toolName)}"
                            is AgentLoop.Status.ToolDone -> null
                        }
                        if (line != null) runCatching { transport.sendAgentStatus(line) }
                    },
                )
                when (outcome) {
                    is AgentLoop.Outcome.Done -> outcome.text
                    is AgentLoop.Outcome.HitMaxIterations ->
                        outcome.partialText ?: "Agent: hit max iterations with no answer."
                    is AgentLoop.Outcome.Failed -> "Agent: ${outcome.message}"
                }.trimForWatch()
            }
        }
    }

    /**
     * Runs [block] (the LLM work) while pinging the watch with a periodic
     * `agent_status`. The watch re-arms its 15 s response timer on each
     * `agent_status` (watch processor.c), so a long single LLM round — a
     * reasoning model thinking for 30-60 s — no longer leaves the watch silent
     * until it times out. Covers the one-shot `complete()` path (which sends no
     * status of its own) and intra-round gaps in the agent-loop path. Cancelled
     * the instant [block] returns, so fast replies never emit a heartbeat.
     */
    private suspend fun <T> withWatchHeartbeat(block: suspend () -> T): T = coroutineScope {
        val heartbeat = launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { transport.sendAgentStatus("→ thinking…") }
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancel()
        }
    }

    /** Last-line defence: a verbose LLM still gets truncated before it
     *  exceeds the watch's AppMessage text buffer / chat-line budget. */
    private fun String.trimForWatch(): String =
        if (length <= WATCH_MAX_CHARS) this
        else take(WATCH_MAX_CHARS - 1).trimEnd() + "…"

    /** Convert an LlmTool wireName (`integration__tool`) to a display
     *  form (`integration.tool`) for the watch's hint-bar status line.
     *  Falls back to the raw wireName when the prefix isn't present. */
    private fun friendly(wireName: String): String =
        com.lazydevs.wristotle.speech.nlu.agent.LlmTool.parseWireName(wireName)
            ?.let { (integration, tool) -> "$integration.$tool" }
            ?: wireName

    private fun renderComplete(r: LlmResult): String = when (r) {
        is LlmResult.Success -> r.text
        is LlmResult.Failure.NoKey -> NO_KEY_HINT
        is LlmResult.Failure.BadKey -> "Agent: bad API key.\n${r.message}"
        is LlmResult.Failure.RateLimit -> "Agent: rate limited.\n${r.message}"
        is LlmResult.Failure.Network -> "Agent: network error.\n${r.message}"
        is LlmResult.Failure.Other -> "Agent: ${r.message}"
    }

    private companion object {
        const val NO_QUERY_HINT =
            "Ask what?\nTry \"ask agent what's the capital of France\"."
        const val NO_KEY_HINT =
            "No agent key set.\nSettings → ✨ Ask Agent → paste your API key."

        /** Soft cap chosen to keep one reply within ~4–5 lines on a 144-px Pebble
         *  chat surface; well under the AppMessage Text payload limit. */
        const val WATCH_MAX_CHARS = 280

        /** Heartbeat cadence — comfortably under the watch's 15 s response
         *  timer so each ping re-arms it with margin. */
        const val HEARTBEAT_INTERVAL_MS = 10_000L
    }
}