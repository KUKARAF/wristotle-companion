// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.agent

/**
 * Provider-agnostic LLM client. Implementations marshal their HTTP
 * work onto [kotlinx.coroutines.Dispatchers.IO] themselves so callers
 * can stay on the main dispatcher without ceremony. Expected wire-
 * level failures (network, 4xx, etc.) surface as [LlmResult.Failure]
 * or [LlmResponse.Failure] — never thrown — so the handler's loop
 * can decide whether to give up or retry.
 */
interface LlmClient {

    /**
     * One-shot Q&A — no tools. [history] carries prior conversation turns
     * (alternating User/Assistant, oldest first) to prepend before the current
     * query so follow-ups have context; empty = single-turn.
     */
    suspend fun complete(
        userQuery: String,
        systemPrompt: String?,
        history: List<LlmMessage> = emptyList(),
    ): LlmResult

    /**
     * Multi-turn chat with optional tool-calling. The provider may
     * return text only (final answer) or text + tool calls the caller
     * must execute and feed back as [LlmMessage.Tool] messages in the
     * next call. Implementations send the [tools] schema verbatim to
     * the provider's native tool-calling API (Anthropic Messages
     * `tools`, OpenAI Chat Completions `tools`).
     */
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<LlmTool>,
    ): LlmResponse
}

sealed interface LlmResponse {
    /** Assistant text + zero-or-more tool calls. `text` is null when the
     *  model returned a pure-tool-call turn with no commentary. */
    data class Ok(val text: String?, val toolCalls: List<LlmToolCall>) : LlmResponse

    /** Same shape as [LlmResult.Failure] but for the chat path. */
    data class Failure(val failure: LlmResult.Failure) : LlmResponse
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