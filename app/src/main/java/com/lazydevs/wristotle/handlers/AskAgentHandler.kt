package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.agent.AskAgentSettings
import com.lazydevs.wristotle.agent.LlmResult
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Read-only by construction — not in the confirm-before-dispatch set.
 * Reads `settings.activeClient()` on every call so a settings edit
 * (new key, new model, new provider) takes effect on the next query
 * without an app restart.
 */
class AskAgentHandler(private val settings: AskAgentSettings) : ActionHandler {

    override val tag: String = "ask-agent"
    override val intent: Intent = Intent.AskAgent

    override suspend fun handle(result: IntentResult): String {
        val query = (result.slots["query"] as? String)?.trim().orEmpty()
        if (query.isEmpty()) return NO_QUERY_HINT
        val systemPrompt = settings.systemPrompt.value.takeIf { it.isNotBlank() }
        return when (val r = settings.activeClient().complete(query, systemPrompt)) {
            is LlmResult.Success -> r.text
            is LlmResult.Failure.NoKey -> NO_KEY_HINT
            is LlmResult.Failure.BadKey -> "Agent: bad API key.\n${r.message}"
            is LlmResult.Failure.RateLimit -> "Agent: rate limited.\n${r.message}"
            is LlmResult.Failure.Network -> "Agent: network error.\n${r.message}"
            is LlmResult.Failure.Other -> "Agent: ${r.message}"
        }
    }

    private companion object {
        const val NO_QUERY_HINT =
            "Ask what?\nTry \"ask agent what's the capital of France\"."
        const val NO_KEY_HINT =
            "No agent key set.\nSettings → ✨ Ask Agent → paste your API key."
    }
}
