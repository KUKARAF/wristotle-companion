package com.lazydevs.wristotle.agent

/**
 * Provider-agnostic LLM client for the AskAgent intent (phase B1 — no
 * MCP tool calling yet; that's B2). One method, one suspending call,
 * one round-trip. Streaming + multi-turn history are deliberately
 * out-of-scope for B1.
 *
 * Implementations must marshal their HTTP work onto
 * [kotlinx.coroutines.Dispatchers.IO] themselves so callers can stay
 * on the main dispatcher without ceremony.
 */
interface LlmClient {
    /**
     * Send [userQuery] (optionally with a [systemPrompt]) and return the
     * model's response text. Errors surface as [LlmResult.Failure] —
     * implementations should NOT throw for expected wire-level problems
     * (network, 4xx, etc.); unexpected exceptions still propagate so
     * the handler's outer try/catch can log them.
     */
    suspend fun complete(userQuery: String, systemPrompt: String?): LlmResult
}

sealed interface LlmResult {
    data class Success(val text: String) : LlmResult

    sealed interface Failure : LlmResult {
        val message: String

        /** No API key configured for the selected provider. */
        data class NoKey(override val message: String = "no API key configured") : Failure

        /** Provider returned 401 — key invalid or revoked. */
        data class BadKey(override val message: String) : Failure

        /** Provider returned 429 — rate-limited or quota exhausted. */
        data class RateLimit(override val message: String) : Failure

        /** Any other wire-level failure — DNS, timeout, 5xx, parse error. */
        data class Network(override val message: String) : Failure

        /** Provider returned a structured error we couldn't categorize. */
        data class Other(override val message: String) : Failure
    }
}

/**
 * Which provider's wire shape to speak. B1 ships these two; a future
 * Google/Anthropic-Bedrock/etc. addition gets a new enum variant + a
 * new [LlmClient] impl. The picker UI keys off this enum.
 */
enum class LlmProvider {
    /** Anthropic Messages API (`api.anthropic.com/v1/messages`). */
    ANTHROPIC,

    /**
     * OpenAI Chat Completions wire shape. Endpoint URL is user-supplied
     * because the same protocol is spoken by OpenAI proper, OpenRouter,
     * many local servers (Ollama, llama.cpp), and self-hosted gateways.
     */
    OPENAI_COMPATIBLE,
}
