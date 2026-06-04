package com.lazydevs.wristotle.stt

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speech-to-text provider settings — chooses between on-device Whisper,
 * cloud / self-hosted HTTP, and the fallback behaviour between them.
 *
 * Companion-local; not mirrored to the watch. The watch sees only the
 * final transcript bubble — whichever recognizer produced it is opaque
 * to the watch side.
 *
 * Mirror of `AskAgentSettings` shape for consistency: per-key
 * `SharedPrefs` + per-field `StateFlow` for Settings UI reactivity.
 * The single HTTP config (base URL + bearer key + model) covers every
 * OpenAI-compatible `/audio/transcriptions` provider — see
 * `speech/.../HttpRecognizer.kt` for the wire shape.
 */
class SttProviderSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readMode())
    /** Which recognizer is primary, and what happens on failure. */
    val mode: StateFlow<SttProviderMode> = _mode

    private val _httpBaseUrl = MutableStateFlow(
        prefs.getString(KEY_HTTP_BASE_URL, null) ?: DEFAULT_BASE_URL,
    )
    val httpBaseUrl: StateFlow<String> = _httpBaseUrl

    private val _httpApiKey = MutableStateFlow(prefs.getString(KEY_HTTP_API_KEY, "").orEmpty())
    val httpApiKey: StateFlow<String> = _httpApiKey

    private val _httpModel = MutableStateFlow(
        prefs.getString(KEY_HTTP_MODEL, null) ?: DEFAULT_MODEL,
    )
    val httpModel: StateFlow<String> = _httpModel

    fun setMode(value: SttProviderMode) {
        if (_mode.value == value) return
        prefs.edit().putString(KEY_MODE, value.name).apply()
        _mode.value = value
    }

    fun setHttpBaseUrl(value: String) = write(KEY_HTTP_BASE_URL, value.trim(), _httpBaseUrl)
    fun setHttpApiKey(value: String) = write(KEY_HTTP_API_KEY, value.trim(), _httpApiKey)
    fun setHttpModel(value: String) = write(KEY_HTTP_MODEL, value.trim(), _httpModel)

    private fun readMode(): SttProviderMode {
        val name = prefs.getString(KEY_MODE, null) ?: return SttProviderMode.LOCAL_ONLY
        return runCatching { SttProviderMode.valueOf(name) }.getOrDefault(SttProviderMode.LOCAL_ONLY)
    }

    private fun write(key: String, value: String, flow: MutableStateFlow<String>) {
        if (flow.value == value) return
        prefs.edit().putString(key, value).apply()
        flow.value = value
    }

    companion object {
        private const val PREFS_NAME = "stt_provider_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_HTTP_BASE_URL = "http_base_url"
        private const val KEY_HTTP_API_KEY = "http_api_key"
        private const val KEY_HTTP_MODEL = "http_model"

        /** Empty by design — see `feedback_no_prefilled_provider_defaults`. */
        const val DEFAULT_BASE_URL = ""

        /** Empty by design — see `feedback_no_prefilled_provider_defaults`. */
        const val DEFAULT_MODEL = ""
    }
}

/**
 * Tri-state recognizer mode. The first option is the v1 default and
 * preserves existing behaviour — users who never touch this setting
 * keep on-device Whisper exactly as before.
 */
enum class SttProviderMode {
    /** Whisper on-device only. Cloud config (if any) is ignored. */
    LOCAL_ONLY,

    /** Whisper on-device tried first; on failure (model not loaded,
     *  inference error) the HTTP recognizer is invoked as a fallback. */
    LOCAL_PRIMARY,

    /** HTTP recognizer tried first; on failure (timeout, network,
     *  non-2xx) the on-device Whisper is invoked as a fallback. */
    CLOUD_PRIMARY,
}
