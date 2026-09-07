// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.agent.AnthropicLlmClient
import com.lazydevs.wristotle.speech.nlu.agent.LlmClient
import com.lazydevs.wristotle.speech.nlu.agent.LlmProvider
import com.lazydevs.wristotle.speech.nlu.agent.OpenAiCompatibleLlmClient
import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.slots.AskAgentTriggers
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import com.lazydevs.wristotle.speech.nlu.store.getEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion-local LLM settings. Per-provider keys are stored
 * separately so the user can flip providers without losing the other
 * key. No watch mirror — entirely companion-side; the watch only sees
 * the final response text.
 *
 * R5 batch 3 — lifted from :app onto the [KeyValueStore] seam. The
 * [activeClient] factory now takes the [HttpClient] explicitly (it was
 * the entanglement that kept this class :app-side until B9 + B5.2
 * shipped the seam and the lifted LLM clients).
 */
class AskAgentSettings(
    private val store: KeyValueStore,
    private val http: HttpClient,
) {

    private val _provider = MutableStateFlow(readProvider())
    val provider: StateFlow<LlmProvider> = _provider

    private val _anthropicApiKey = MutableStateFlow(store.getString(KEY_ANTHROPIC_API_KEY, ""))
    val anthropicApiKey: StateFlow<String> = _anthropicApiKey

    private val _anthropicModel = MutableStateFlow(readOrDefault(KEY_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL))
    val anthropicModel: StateFlow<String> = _anthropicModel

    private val _openaiEndpoint = MutableStateFlow(
        readOrDefault(KEY_OPENAI_ENDPOINT, OpenAiCompatibleLlmClient.DEFAULT_ENDPOINT_URL),
    )
    val openaiEndpoint: StateFlow<String> = _openaiEndpoint

    private val _openaiApiKey = MutableStateFlow(store.getString(KEY_OPENAI_API_KEY, ""))
    val openaiApiKey: StateFlow<String> = _openaiApiKey

    private val _openaiModel = MutableStateFlow(readOrDefault(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL))
    val openaiModel: StateFlow<String> = _openaiModel

    private val _systemPrompt = MutableStateFlow(readOrDefault(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT))
    val systemPrompt: StateFlow<String> = _systemPrompt

    private val _customTriggers = MutableStateFlow(
        AskAgentTriggers.sanitise(store.getString(KEY_CUSTOM_TRIGGERS, "")),
    )
    val customTriggers: StateFlow<List<String>> = _customTriggers

    private val _anthropicWebSearch = MutableStateFlow(
        store.getBoolean(KEY_ANTHROPIC_WEB_SEARCH, false),
    )
    val anthropicWebSearch: StateFlow<Boolean> = _anthropicWebSearch

    /** LLM response timeout in seconds. Raised for slow local reasoning models
     *  (the hardcoded 14 s timed them out mid-generation). Clamped on read so a
     *  stale/garbage stored value can't disable the timeout entirely. */
    private val _responseTimeoutSec = MutableStateFlow(
        store.getInt(KEY_RESPONSE_TIMEOUT_SEC, DEFAULT_RESPONSE_TIMEOUT_SEC)
            .coerceIn(MIN_RESPONSE_TIMEOUT_SEC, MAX_RESPONSE_TIMEOUT_SEC),
    )
    val responseTimeoutSec: StateFlow<Int> = _responseTimeoutSec

    /** How free voice is routed relative to Ask Agent — see
     *  [AgentRoutingMode]. Default [AgentRoutingMode.OFF] keeps the
     *  NLU-first behaviour. */
    private val _agentRoutingMode = MutableStateFlow(store.getEnum(KEY_AGENT_ROUTING_MODE, AgentRoutingMode.OFF))
    val agentRoutingMode: StateFlow<AgentRoutingMode> = _agentRoutingMode

    fun setProvider(value: LlmProvider) {
        store.putString(KEY_PROVIDER, value.name)
        _provider.value = value
    }

    fun setAnthropicApiKey(value: String) = write(KEY_ANTHROPIC_API_KEY, value.trim(), _anthropicApiKey)
    fun setAnthropicModel(value: String) = write(KEY_ANTHROPIC_MODEL, value.trim(), _anthropicModel)
    fun setAnthropicWebSearch(value: Boolean) {
        if (_anthropicWebSearch.value == value) return
        store.putBoolean(KEY_ANTHROPIC_WEB_SEARCH, value)
        _anthropicWebSearch.value = value
    }
    fun setOpenAiEndpoint(value: String) = write(KEY_OPENAI_ENDPOINT, value.trim(), _openaiEndpoint)
    fun setOpenAiApiKey(value: String) = write(KEY_OPENAI_API_KEY, value.trim(), _openaiApiKey)
    fun setOpenAiModel(value: String) = write(KEY_OPENAI_MODEL, value.trim(), _openaiModel)
    fun setSystemPrompt(value: String) = write(KEY_SYSTEM_PROMPT, value, _systemPrompt)
    fun resetSystemPromptToDefault() = setSystemPrompt(DEFAULT_SYSTEM_PROMPT)

    fun setResponseTimeoutSec(value: Int) {
        val clamped = value.coerceIn(MIN_RESPONSE_TIMEOUT_SEC, MAX_RESPONSE_TIMEOUT_SEC)
        if (_responseTimeoutSec.value == clamped) return
        store.putInt(KEY_RESPONSE_TIMEOUT_SEC, clamped)
        _responseTimeoutSec.value = clamped
    }

    fun setAgentRoutingMode(value: AgentRoutingMode) {
        if (_agentRoutingMode.value == value) return
        store.putString(KEY_AGENT_ROUTING_MODE, value.name)
        _agentRoutingMode.value = value
    }

    /**
     * True when the active provider has enough configured to actually run:
     * Anthropic needs an API key; an OpenAI-compatible endpoint (often a
     * self-hosted server with no key) needs at least a base URL. Gates the
     * [AgentRoutingMode] fallback/agent-only paths so enabling them without a
     * provider can't silently swallow every query.
     */
    fun isConfigured(): Boolean = when (_provider.value) {
        LlmProvider.ANTHROPIC -> _anthropicApiKey.value.isNotBlank()
        LlmProvider.OPENAI_COMPATIBLE -> _openaiEndpoint.value.isNotBlank()
    }

    fun setCustomTriggers(rawText: String) {
        val sanitised = AskAgentTriggers.sanitise(rawText)
        if (sanitised == _customTriggers.value) return
        store.putString(KEY_CUSTOM_TRIGGERS, sanitised.joinToString("\n"))
        _customTriggers.value = sanitised
    }

    /**
     * Built per call (not cached) so a settings edit takes effect on
     * the next voice query without an app restart. Construction is
     * cheap; the cost is the HTTP round-trip, not the client object.
     */
    fun activeClient(): LlmClient = when (_provider.value) {
        LlmProvider.ANTHROPIC -> AnthropicLlmClient(
            http = http,
            apiKey = _anthropicApiKey.value,
            model = _anthropicModel.value,
            webSearchEnabled = _anthropicWebSearch.value,
            readTimeoutMs = _responseTimeoutSec.value * 1000,
        )
        LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleLlmClient(
            http = http,
            endpointUrl = _openaiEndpoint.value,
            apiKey = _openaiApiKey.value,
            model = _openaiModel.value,
            readTimeoutMs = _responseTimeoutSec.value * 1000,
        )
    }

    /**
     * True when the active provider has server-side tools that need
     * the chat/tool path even with zero user-configured MCP servers
     * (today: Anthropic with web_search enabled). When false and no
     * MCP servers are configured, AskAgentHandler can take the cheaper
     * one-shot `complete()` route.
     */
    fun activeProviderHasServerTools(): Boolean = when (_provider.value) {
        LlmProvider.ANTHROPIC -> _anthropicWebSearch.value
        LlmProvider.OPENAI_COMPATIBLE -> false
    }

    private fun readProvider(): LlmProvider =
        store.getEnum(KEY_PROVIDER, LlmProvider.ANTHROPIC)

    private fun readOrDefault(key: String, default: String): String {
        val raw = store.getString(key, "")
        return raw.ifEmpty { default }
    }

    private fun write(key: String, value: String, flow: MutableStateFlow<String>) {
        if (flow.value == value) return
        store.putString(key, value)
        flow.value = value
    }

    companion object {
        const val PREFS_NAME = "ask_agent_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_ANTHROPIC_MODEL = "anthropic_model"
        private const val KEY_OPENAI_ENDPOINT = "openai_endpoint"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_CUSTOM_TRIGGERS = "custom_triggers"
        private const val KEY_ANTHROPIC_WEB_SEARCH = "anthropic_web_search"
        private const val KEY_RESPONSE_TIMEOUT_SEC = "response_timeout_sec"
        private const val KEY_AGENT_ROUTING_MODE = "agent_routing_mode"

        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        /** Default LLM response timeout (seconds). Unchanged from the previous
         *  hardcoded 14 s so existing setups behave identically; users with slow
         *  local reasoning models raise it via Settings. Clamped to
         *  [MIN_RESPONSE_TIMEOUT_SEC]..[MAX_RESPONSE_TIMEOUT_SEC]. */
        const val DEFAULT_RESPONSE_TIMEOUT_SEC = 14
        const val MIN_RESPONSE_TIMEOUT_SEC = 5
        const val MAX_RESPONSE_TIMEOUT_SEC = 300

        /**
         * Watch-friendly baseline that lands as the visible default in
         * the Settings card. Steers the LLM toward short plain-text
         * answers — without it, models default to verbose markdown
         * (tables, **bold**, bullets) that renders as raw characters
         * on the Pebble's tiny chat surface. The user can edit or
         * fully clear it.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "Reply in 1-2 short plain-text sentences. Your response is shown " +
                "on a tiny smartwatch chat surface. Do NOT use markdown, tables, " +
                "bullet points, headers, or emoji. No code blocks. No bold or " +
                "italics. Plain text only, under 200 characters when possible."
    }
}
