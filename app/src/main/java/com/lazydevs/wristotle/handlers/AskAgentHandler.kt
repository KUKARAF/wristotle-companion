package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.agent.AgentLoop
import com.lazydevs.wristotle.agent.AskAgentSettings
import com.lazydevs.wristotle.agent.LlmResult
import com.lazydevs.wristotle.mcp.McpServerRepository
import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.transport.PebbleTransport

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
 * are sent to the watch via [PebbleTransport.sendResponse] so the
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
    private val transport: PebbleTransport,
) : ActionHandler {

    override val tag: String = "ask-agent"
    override val intent: Intent = Intent.AskAgent

    override suspend fun handle(result: IntentResult): String {
        val query = (result.slots[SlotKeys.Query] as? String)?.trim().orEmpty()
        if (query.isEmpty()) return NO_QUERY_HINT
        // System prompt is the user-visible value from Settings (default
        // is the watch-friendly baseline; user can edit or clear it via
        // the card's "Reset to default" button). No hidden prepend —
        // what you see in Settings is what gets sent.
        val systemPrompt = settings.systemPrompt.value.takeIf { it.isNotBlank() }
        val client = settings.activeClient()

        val enabled = mcpServers.listEnabled()
        if (enabled.isEmpty()) {
            return renderComplete(client.complete(query, systemPrompt)).trimForWatch()
        }

        val loop = AgentLoop(client)
        val outcome = loop.run(
            userQuery = query,
            systemPrompt = systemPrompt,
            servers = enabled,
            // Per-round watch status (B3): goes to the hint-bar slot via
            // sendAgentStatus, NOT sendResponse. The chat surface only
            // renders one bubble per query — using the response key here
            // would drop the final answer (see
            // feedback_watch_chat_single_bubble memory).
            //
            // Thinking AND CallingTool both emit so the watch's 15 s
            // response timer is re-armed at the start of each LLM round
            // (otherwise a pure-text call taking >15 s leaves the watch
            // silent until it times out — companion's eventual response
            // would land too late to render).
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
        return when (outcome) {
            is AgentLoop.Outcome.Done -> outcome.text
            is AgentLoop.Outcome.HitMaxIterations ->
                outcome.partialText ?: "Agent: hit max iterations with no answer."
            is AgentLoop.Outcome.Failed -> "Agent: ${outcome.message}"
        }.trimForWatch()
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
        com.lazydevs.wristotle.agent.LlmTool.parseWireName(wireName)
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
    }
}
