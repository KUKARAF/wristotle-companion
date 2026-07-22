// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.homeassistant.HomeAssistantClient
import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.slots.HomeAssistantTriggers
import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Companion-local Home Assistant settings on the [com.lazydevs.wristotle.speech.nlu.store.KeyValueStore]
 * seam. Base URL + long-lived access token point at the user's self-hosted
 * HA; [customTriggers] are extra wake words (e.g. "jarvis"). No watch mirror —
 * the watch only ever sees the final reply text.
 *
 * Mirrors [AskAgentSettings]'s shape (StateFlow-per-field, per-field writer
 * that no-ops when unchanged, per-call client factory) minus the LLM provider
 * fan-out — there's exactly one backend.
 */
class HomeAssistantSettings(
    private val store: KeyValueStore,
    private val http: HttpClient,
) {

    private val _baseUrl = MutableStateFlow(store.getString(KEY_BASE_URL, ""))
    val baseUrl: StateFlow<String> = _baseUrl

    private val _token = MutableStateFlow(store.getString(KEY_TOKEN, ""))
    val token: StateFlow<String> = _token

    private val _language = MutableStateFlow(readOrDefault(KEY_LANGUAGE, HomeAssistantClient.DEFAULT_LANGUAGE))
    val language: StateFlow<String> = _language

    private val _customTriggers = MutableStateFlow(
        HomeAssistantTriggers.sanitise(store.getString(KEY_CUSTOM_TRIGGERS, "")),
    )
    val customTriggers: StateFlow<List<String>> = _customTriggers

    /** Response timeout in seconds. Clamped on read so a stale/garbage stored
     *  value can't disable the timeout entirely. */
    private val _responseTimeoutSec = MutableStateFlow(
        store.getInt(KEY_RESPONSE_TIMEOUT_SEC, DEFAULT_RESPONSE_TIMEOUT_SEC)
            .coerceIn(MIN_RESPONSE_TIMEOUT_SEC, MAX_RESPONSE_TIMEOUT_SEC),
    )
    val responseTimeoutSec: StateFlow<Int> = _responseTimeoutSec

    /** True once the user has entered both a base URL and a token — the
     *  minimum for [client] to reach HA. Drives the "not set up yet" hint. */
    val isConfigured: Boolean
        get() = _baseUrl.value.isNotBlank() && _token.value.isNotBlank()

    fun setBaseUrl(value: String) = write(KEY_BASE_URL, value.trim(), _baseUrl)
    fun setToken(value: String) = write(KEY_TOKEN, value.trim(), _token)
    fun setLanguage(value: String) = write(KEY_LANGUAGE, value.trim(), _language)

    fun setResponseTimeoutSec(value: Int) {
        val clamped = value.coerceIn(MIN_RESPONSE_TIMEOUT_SEC, MAX_RESPONSE_TIMEOUT_SEC)
        if (_responseTimeoutSec.value == clamped) return
        store.putInt(KEY_RESPONSE_TIMEOUT_SEC, clamped)
        _responseTimeoutSec.value = clamped
    }

    fun setCustomTriggers(rawText: String) {
        val sanitised = HomeAssistantTriggers.sanitise(rawText)
        if (sanitised == _customTriggers.value) return
        store.putString(KEY_CUSTOM_TRIGGERS, sanitised.joinToString("\n"))
        _customTriggers.value = sanitised
    }

    /**
     * Built per call (not cached) so a Settings edit takes effect on the next
     * voice command without an app restart. Construction is cheap; the cost
     * is the HTTP round-trip.
     */
    fun client(): HomeAssistantClient = HomeAssistantClient(
        http = http,
        baseUrl = _baseUrl.value,
        token = _token.value,
        language = _language.value.ifBlank { HomeAssistantClient.DEFAULT_LANGUAGE },
        readTimeoutMs = _responseTimeoutSec.value * 1000,
    )

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
        const val PREFS_NAME = "home_assistant_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CUSTOM_TRIGGERS = "custom_triggers"
        private const val KEY_RESPONSE_TIMEOUT_SEC = "response_timeout_sec"

        const val DEFAULT_RESPONSE_TIMEOUT_SEC = 10
        const val MIN_RESPONSE_TIMEOUT_SEC = 5
        const val MAX_RESPONSE_TIMEOUT_SEC = 120
    }
}
