// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.http.HttpClient
import com.lazydevs.wristotle.speech.nlu.http.HttpRequest
import com.lazydevs.wristotle.speech.nlu.http.HttpResponse
import com.lazydevs.wristotle.speech.nlu.tts.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Response timeout knob — added so slow local reasoning models (Gemma/R1-style
 * thinking) aren't cut off mid-generation by the old hardcoded 14 s. Clamped on
 * BOTH read and write to [AskAgentSettings.MIN_RESPONSE_TIMEOUT_SEC]..
 * [AskAgentSettings.MAX_RESPONSE_TIMEOUT_SEC].
 */
class AskAgentSettingsTest {

    private object StubHttp : HttpClient {
        override suspend fun request(request: HttpRequest): HttpResponse =
            throw UnsupportedOperationException("HTTP not exercised in settings tests")
    }

    private fun settings(store: InMemoryKeyValueStore = InMemoryKeyValueStore()) =
        AskAgentSettings(store, StubHttp)

    @Test fun responseTimeout_defaultsToPreviousHardcodedValue() {
        // Unchanged from the old hardcoded 14 s so existing setups behave identically.
        assertEquals(AskAgentSettings.DEFAULT_RESPONSE_TIMEOUT_SEC, settings().responseTimeoutSec.value)
        assertEquals(14, AskAgentSettings.DEFAULT_RESPONSE_TIMEOUT_SEC)
    }

    @Test fun responseTimeout_persistsAValidValue() {
        val s = settings()
        s.setResponseTimeoutSec(120)
        assertEquals(120, s.responseTimeoutSec.value)
    }

    @Test fun responseTimeout_clampsWriteToRange() {
        val s = settings()
        s.setResponseTimeoutSec(9999)
        assertEquals(AskAgentSettings.MAX_RESPONSE_TIMEOUT_SEC, s.responseTimeoutSec.value)
        s.setResponseTimeoutSec(1)
        assertEquals(AskAgentSettings.MIN_RESPONSE_TIMEOUT_SEC, s.responseTimeoutSec.value)
    }

    @Test fun responseTimeout_clampsAGarbageStoredValueOnRead() {
        val store = InMemoryKeyValueStore()
        store.putInt("response_timeout_sec", 99_999)
        assertEquals(AskAgentSettings.MAX_RESPONSE_TIMEOUT_SEC, settings(store).responseTimeoutSec.value)
    }
}
