package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.agent.AgentLoop
import com.lazydevs.wristotle.agent.AskAgentSettings
import com.lazydevs.wristotle.agent.LlmResult
import com.lazydevs.wristotle.mcp.McpServerRepository
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
        val query = (result.slots["query"] as? String)?.trim().orEmpty()
        if (query.isEmpty()) return NO_QUERY_HINT
        val systemPrompt = composedSystemPrompt(settings.systemPrompt.value)
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
            // Per-round status to the watch is DISABLED for phase B2 — the
            // watch's chat surface only renders one bubble per query, so
            // an intermediate "🛠 tool" send blocks the final answer from
            // displaying. Per-round visibility now lives in Logcat only;
            // proper watch-side status surfacing needs a new AppMessage
            // key + watch UI change (see TODO).
            onStatus = { /* no-op */ },
        )
        return when (outcome) {
            is AgentLoop.Outcome.Done -> outcome.text
            is AgentLoop.Outcome.HitMaxIterations ->
                outcome.partialText ?: "Agent: hit max iterations with no answer."
            is AgentLoop.Outcome.Failed -> "Agent: ${outcome.message}"
        }.trimForWatch()
    }

    /**
     * Baseline system prompt steers the LLM toward Pebble-friendly output:
     * the watch's chat surface shows a handful of lines of plain text — no
     * markdown rendering, no tables, no bullets. Concatenated with the
     * user's optional override so their tone/persona instructions
     * compose on top.
     */
    private fun composedSystemPrompt(userOverride: String): String {
        val base = WATCH_SYSTEM_PROMPT
        return if (userOverride.isBlank()) base else "$base\n\n$userOverride"
    }

    /** Last-line defence: a verbose LLM still gets truncated before it
     *  exceeds the watch's AppMessage text buffer / chat-line budget. */
    private fun String.trimForWatch(): String =
        if (length <= WATCH_MAX_CHARS) this
        else take(WATCH_MAX_CHARS - 1).trimEnd() + "…"

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

        const val WATCH_SYSTEM_PROMPT =
            "Reply in 1–2 short plain-text sentences. Your response is shown on a tiny smartwatch chat surface. " +
                "Do NOT use markdown, tables, bullet points, headers, or emoji. " +
                "No code blocks. No bold or italics. Plain text only, under 200 characters when possible."

        /** Soft cap chosen to keep one reply within ~4–5 lines on a 144-px Pebble
         *  chat surface; well under the AppMessage Text payload limit. */
        const val WATCH_MAX_CHARS = 280
    }
}
