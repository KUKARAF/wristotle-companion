package com.lazydevs.wristotle.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion-local LLM settings. Per-provider keys are stored
 * separately so the user can flip providers without losing the other
 * key. No watch mirror — entirely companion-side; the watch only sees
 * the final response text.
 */
class AskAgentSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _provider = MutableStateFlow(readProvider())
    val provider: StateFlow<LlmProvider> = _provider

    private val _anthropicApiKey = MutableStateFlow(prefs.getString(KEY_ANTHROPIC_API_KEY, "").orEmpty())
    val anthropicApiKey: StateFlow<String> = _anthropicApiKey

    private val _anthropicModel = MutableStateFlow(
        prefs.getString(KEY_ANTHROPIC_MODEL, null) ?: DEFAULT_ANTHROPIC_MODEL,
    )
    val anthropicModel: StateFlow<String> = _anthropicModel

    private val _openaiEndpoint = MutableStateFlow(
        prefs.getString(KEY_OPENAI_ENDPOINT, null) ?: OpenAiCompatibleLlmClient.DEFAULT_ENDPOINT_URL,
    )
    val openaiEndpoint: StateFlow<String> = _openaiEndpoint

    private val _openaiApiKey = MutableStateFlow(prefs.getString(KEY_OPENAI_API_KEY, "").orEmpty())
    val openaiApiKey: StateFlow<String> = _openaiApiKey

    private val _openaiModel = MutableStateFlow(
        prefs.getString(KEY_OPENAI_MODEL, null) ?: DEFAULT_OPENAI_MODEL,
    )
    val openaiModel: StateFlow<String> = _openaiModel

    private val _systemPrompt = MutableStateFlow(prefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty())
    val systemPrompt: StateFlow<String> = _systemPrompt

    fun setProvider(value: LlmProvider) {
        prefs.edit().putString(KEY_PROVIDER, value.name).apply()
        _provider.value = value
    }

    fun setAnthropicApiKey(value: String) = write(KEY_ANTHROPIC_API_KEY, value.trim(), _anthropicApiKey)
    fun setAnthropicModel(value: String) = write(KEY_ANTHROPIC_MODEL, value.trim(), _anthropicModel)
    fun setOpenAiEndpoint(value: String) = write(KEY_OPENAI_ENDPOINT, value.trim(), _openaiEndpoint)
    fun setOpenAiApiKey(value: String) = write(KEY_OPENAI_API_KEY, value.trim(), _openaiApiKey)
    fun setOpenAiModel(value: String) = write(KEY_OPENAI_MODEL, value.trim(), _openaiModel)
    fun setSystemPrompt(value: String) = write(KEY_SYSTEM_PROMPT, value, _systemPrompt)

    /**
     * Built per call (not cached) so a settings edit takes effect on
     * the next voice query without an app restart. Construction is
     * cheap; the cost is the HTTP round-trip, not the client object.
     */
    fun activeClient(): LlmClient = when (_provider.value) {
        LlmProvider.ANTHROPIC -> AnthropicLlmClient(
            apiKey = _anthropicApiKey.value,
            model = _anthropicModel.value,
        )
        LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleLlmClient(
            endpointUrl = _openaiEndpoint.value,
            apiKey = _openaiApiKey.value,
            model = _openaiModel.value,
        )
    }

    private fun readProvider(): LlmProvider {
        val name = prefs.getString(KEY_PROVIDER, null) ?: return LlmProvider.ANTHROPIC
        return runCatching { LlmProvider.valueOf(name) }.getOrDefault(LlmProvider.ANTHROPIC)
    }

    private fun write(key: String, value: String, flow: MutableStateFlow<String>) {
        if (flow.value == value) return
        prefs.edit().putString(key, value).apply()
        flow.value = value
    }

    companion object {
        private const val PREFS_NAME = "ask_agent_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_ANTHROPIC_MODEL = "anthropic_model"
        private const val KEY_OPENAI_ENDPOINT = "openai_endpoint"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        // Defaults: cheap + decent + broadly available. Users override
        // per-provider via the Settings card; field is a plain text
        // input so any provider's model id works.
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    }
}
