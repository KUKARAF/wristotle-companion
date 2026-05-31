package com.lazydevs.wristotle.agent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion-local settings for the AskAgent intent (phase B1). Picks
 * which [LlmProvider] is in use plus the API key + model name for
 * each. SharedPreferences-backed; the Settings card observes the
 * [StateFlow]s and writes through the setters.
 *
 * Per-provider keys are stored separately so the user can flip
 * between providers without losing their keys. Active provider's
 * client is instantiated lazily by [AskAgentHandler].
 *
 * No watch mirroring — this is entirely companion-side. The watch
 * sends a transcript over the existing `companion_query` channel and
 * receives the LLM response over the existing response key.
 */
class AskAgentSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _provider = MutableStateFlow(readProvider())
    val provider: StateFlow<LlmProvider> = _provider

    private val _anthropicApiKey = MutableStateFlow(prefs.getString(KEY_ANTHROPIC_API_KEY, "")!!)
    val anthropicApiKey: StateFlow<String> = _anthropicApiKey

    private val _anthropicModel = MutableStateFlow(
        prefs.getString(KEY_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL)!!,
    )
    val anthropicModel: StateFlow<String> = _anthropicModel

    private val _openaiEndpoint = MutableStateFlow(
        prefs.getString(KEY_OPENAI_ENDPOINT, OpenAiCompatibleLlmClient.DEFAULT_ENDPOINT_URL)!!,
    )
    val openaiEndpoint: StateFlow<String> = _openaiEndpoint

    private val _openaiApiKey = MutableStateFlow(prefs.getString(KEY_OPENAI_API_KEY, "")!!)
    val openaiApiKey: StateFlow<String> = _openaiApiKey

    private val _openaiModel = MutableStateFlow(
        prefs.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL)!!,
    )
    val openaiModel: StateFlow<String> = _openaiModel

    private val _systemPrompt = MutableStateFlow(prefs.getString(KEY_SYSTEM_PROMPT, "")!!)
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
     * Build the [LlmClient] for the currently-active provider, reading
     * the latest snapshot of all settings. Called per-call by
     * [AskAgentHandler] so a settings edit takes effect on the very
     * next voice query (no app restart).
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

        /**
         * Defaults aimed at "cheap, decent, available right now":
         *  - Anthropic → Sonnet 4.6 (matches the current docs landing).
         *  - OpenAI    → gpt-4o-mini (cheap, fast, broadly available).
         * Users override per-provider via the Settings card; the field
         * is a plain text input so any provider's model id works.
         */
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    }
}
