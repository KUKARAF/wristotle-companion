// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import com.lazydevs.wristotle.speech.nlu.store.getEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Text-to-speech provider settings — chooses between the on-device
 * Android `TextToSpeech` engine and a cloud / self-hosted HTTP endpoint,
 * plus the fallback behaviour between them. Mirrors the STT provider
 * shape ([SttProviderSettings]) so users see the same mental model on
 * both ends of the voice loop.
 *
 * Single HTTP config (base URL + bearer key + model + voice) covers every
 * OpenAI-compatible `/audio/speech` provider — OpenAI itself, OpenedAI
 * Speech (Docker self-host), LiteLLM proxies in front of Piper or Coqui,
 * etc. See `tts/HttpTtsClient.kt` for the wire shape.
 *
 * Companion-local; not mirrored to the watch — the watch just receives
 * the PCM and plays it. Empty-string defaults for URL / key / model /
 * voice matter (see `feedback_no_prefilled_provider_defaults`).
 */
class TtsProviderSettings(private val store: KeyValueStore) {

    private val _mode = MutableStateFlow(readMode())
    /** Which engine is primary, and what happens on failure. */
    val mode: StateFlow<TtsProviderMode> = _mode

    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED, false))
    /** Master switch — when off, no watch-side TTS is attempted at all. */
    val enabled: StateFlow<Boolean> = _enabled

    private val _httpBaseUrl = MutableStateFlow(store.getString(KEY_HTTP_BASE_URL, ""))
    val httpBaseUrl: StateFlow<String> = _httpBaseUrl

    private val _httpApiKey = MutableStateFlow(store.getString(KEY_HTTP_API_KEY, ""))
    val httpApiKey: StateFlow<String> = _httpApiKey

    private val _httpModel = MutableStateFlow(store.getString(KEY_HTTP_MODEL, ""))
    val httpModel: StateFlow<String> = _httpModel

    private val _httpVoice = MutableStateFlow(store.getString(KEY_HTTP_VOICE, ""))
    val httpVoice: StateFlow<String> = _httpVoice

    private val _intentsEnabled = MutableStateFlow(readIntentSet())
    /** Set of intent identifiers (e.g. `"AskAgent"`, `"Weather"`) the user
     *  has opted into voicing on the watch. Empty by default — voice is
     *  off everywhere until explicitly enabled per-intent. The master
     *  [enabled] flag still gates the whole feature on top of this. */
    val intentsEnabled: StateFlow<Set<String>> = _intentsEnabled

    fun setMode(value: TtsProviderMode) {
        if (_mode.value == value) return
        store.putString(KEY_MODE, value.name)
        _mode.value = value
    }

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        store.putBoolean(KEY_ENABLED, value)
        _enabled.value = value
    }

    fun setHttpBaseUrl(value: String) = write(KEY_HTTP_BASE_URL, value.trim(), _httpBaseUrl)
    fun setHttpApiKey(value: String) = write(KEY_HTTP_API_KEY, value.trim(), _httpApiKey)
    fun setHttpModel(value: String) = write(KEY_HTTP_MODEL, value.trim(), _httpModel)
    fun setHttpVoice(value: String) = write(KEY_HTTP_VOICE, value.trim(), _httpVoice)

    fun setIntentEnabled(intent: String, on: Boolean) {
        val current = _intentsEnabled.value
        val next = if (on) current + intent else current - intent
        if (current == next) return
        store.putString(KEY_INTENTS_ENABLED, next.joinToString(","))
        _intentsEnabled.value = next
    }

    /** Master + per-intent gate the handler side checks before calling
     *  the streamer. Keeps the conditional out of every handler. */
    fun shouldSpeak(intent: String): Boolean =
        _enabled.value && intent in _intentsEnabled.value

    private fun readMode(): TtsProviderMode =
        store.getEnum(KEY_MODE, TtsProviderMode.LOCAL_ONLY)

    private fun readIntentSet(): Set<String> =
        store.getString(KEY_INTENTS_ENABLED, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun write(key: String, value: String, flow: MutableStateFlow<String>) {
        if (flow.value == value) return
        store.putString(key, value)
        flow.value = value
    }

    companion object {
        /** SharedPreferences file name the Android-side store uses. */
        const val PREFS_NAME = "tts_provider_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HTTP_BASE_URL = "http_base_url"
        private const val KEY_HTTP_API_KEY = "http_api_key"
        private const val KEY_HTTP_MODEL = "http_model"
        private const val KEY_HTTP_VOICE = "http_voice"
        private const val KEY_INTENTS_ENABLED = "intents_enabled"

        /**
         * Stable identifiers for the intents that have a meaningful spoken
         * response on the watch. These match the values of
         * [com.lazydevs.wristotle.speech.nlu.Intent] enum names so the
         * dispatch site can gate with `shouldSpeak(routed.intent.name)`
         * without a mapping table. New intents that warrant TTS extend
         * this list AND gain a row in the Settings card.
         */
        // Long-form conversational
        const val INTENT_ASK_AGENT = "AskAgent"
        const val INTENT_MORNING_BRIEF = "MorningBrief"

        // Quick lookups (short replies)
        const val INTENT_WEATHER = "Weather"
        const val INTENT_WORLD_TIME = "WorldTime"
        const val INTENT_TIME = "Time"
        const val INTENT_BATTERY = "Battery"
        const val INTENT_CALCULATE = "Calculate"

        // Communication confirmations
        const val INTENT_CALL = "Call"
        const val INTENT_SEND_MESSAGE = "SendMessage"

        // Time + schedule confirmations / lists
        const val INTENT_REMINDER = "Reminder"
        const val INTENT_SET_ALARM = "SetAlarm"
        const val INTENT_SET_TIMER = "SetTimer"
        const val INTENT_CALENDAR = "Calendar"
        const val INTENT_CREATE_EVENT = "CreateEvent"

        // Capture confirmations / lists
        const val INTENT_NOTE = "Note"
        const val INTENT_ADD_TASK = "AddTask"
        const val INTENT_LIST_TASKS = "ListTasks"
    }
}

/**
 * Tri-state primary/fallback shape, mirroring [SttProviderMode]:
 *
 *  - [LOCAL_ONLY] — only Android's `TextToSpeech`, never hit HTTP.
 *  - [LOCAL_PRIMARY] — try Android first; if it fails, fall back to HTTP.
 *  - [CLOUD_PRIMARY] — try HTTP first; if it fails (network / 4xx / 5xx),
 *    fall back to Android. The right default for users with a paid API
 *    key or a self-hosted server who want best-quality output but don't
 *    want to lose TTS when the network blips.
 */
enum class TtsProviderMode { LOCAL_ONLY, LOCAL_PRIMARY, CLOUD_PRIMARY }
