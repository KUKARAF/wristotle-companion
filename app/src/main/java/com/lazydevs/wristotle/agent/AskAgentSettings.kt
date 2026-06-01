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

    // On first install (key absent) the watch-friendly baseline lands
    // as the actual visible default in the Settings card — so what the
    // user sees in the field IS what gets sent. An empty value is
    // respected (user explicitly cleared it); resetSystemPromptToDefault()
    // brings the baseline back.
    private val _systemPrompt = MutableStateFlow(
        prefs.getString(KEY_SYSTEM_PROMPT, null) ?: DEFAULT_SYSTEM_PROMPT,
    )
    val systemPrompt: StateFlow<String> = _systemPrompt

    // User-supplied trigger words added on top of the built-in defaults
    // ("agent", "claude", "ai", ...). Comma + newline separated on the
    // wire so the UI can show one-per-line while we persist a compact
    // string. Sanitised on every read so a hand-edited prefs file can't
    // sneak regex metacharacters through.
    private val _customTriggers = MutableStateFlow(
        com.lazydevs.wristotle.nlu.slots.AskAgentTriggers.sanitise(
            prefs.getString(KEY_CUSTOM_TRIGGERS, "").orEmpty(),
        ),
    )
    val customTriggers: StateFlow<List<String>> = _customTriggers

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
    fun resetSystemPromptToDefault() = setSystemPrompt(DEFAULT_SYSTEM_PROMPT)

    /**
     * Update the custom trigger words from the raw text the user typed
     * in Settings (one-per-line is the displayed convention; commas are
     * also accepted). Sanitisation drops blanks + duplicates and
     * lowercases — what we persist is what we'd run on the next query.
     */
    fun setCustomTriggers(rawText: String) {
        val sanitised = com.lazydevs.wristotle.nlu.slots.AskAgentTriggers.sanitise(rawText)
        if (sanitised == _customTriggers.value) return
        prefs.edit().putString(KEY_CUSTOM_TRIGGERS, sanitised.joinToString("\n")).apply()
        _customTriggers.value = sanitised
    }

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
        private const val KEY_CUSTOM_TRIGGERS = "custom_triggers"

        // Defaults: cheap + decent + broadly available. Users override
        // per-provider via the Settings card; field is a plain text
        // input so any provider's model id works.
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

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
