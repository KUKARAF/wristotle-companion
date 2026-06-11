// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for [CompositeTtsProvider] — the primary/fallback decision tree
 * the user picks via the Mode radio on Settings → 🔊 Speech. The mode-
 * to-wire mapping is reused by `WristotleApplication.buildTtsProvider`
 * and `buildPrimaryTtsProvider`, so a regression here breaks both the
 * dispatch path AND the test surface on the Speech card.
 */
class CompositeTtsProviderTest {

    private val happyPayload = ByteArray(8) { it.toByte() }

    private class StaticProvider(
        override val displayName: String,
        private val result: TtsResult,
    ) : TtsProvider {
        var calls = 0
            private set
        override suspend fun synthesizeToWav(text: String): TtsResult {
            calls++
            return result
        }
    }

    @Test fun `forMode LOCAL_ONLY ignores http even when provided`() {
        val local = StaticProvider("Local", TtsResult.Success(happyPayload))
        val http = StaticProvider("HTTP", TtsResult.Success(happyPayload))
        val provider = CompositeTtsProvider.forMode(TtsProviderMode.LOCAL_ONLY, local, http)
        assertEquals("Local", provider.displayName, "no composite wrapper in LOCAL_ONLY mode")
    }

    @Test fun `forMode LOCAL_PRIMARY wires local first http second`() = runTest {
        val local = StaticProvider("Local", TtsResult.Success(happyPayload))
        val http = StaticProvider("HTTP", TtsResult.Success(happyPayload))
        val provider = CompositeTtsProvider.forMode(TtsProviderMode.LOCAL_PRIMARY, local, http)
        provider.synthesizeToWav("hello")
        assertEquals(1, local.calls, "local primary should be tried")
        assertEquals(0, http.calls, "http should NOT be tried when local succeeds")
    }

    @Test fun `forMode CLOUD_PRIMARY wires http first local second`() = runTest {
        val local = StaticProvider("Local", TtsResult.Success(happyPayload))
        val http = StaticProvider("HTTP", TtsResult.Success(happyPayload))
        val provider = CompositeTtsProvider.forMode(TtsProviderMode.CLOUD_PRIMARY, local, http)
        provider.synthesizeToWav("hello")
        assertEquals(1, http.calls, "http primary should be tried")
        assertEquals(0, local.calls, "local should NOT be tried when http succeeds")
    }

    @Test fun `primary failure triggers fallback`() = runTest {
        val local = StaticProvider("Local", TtsResult.Failure("init failed"))
        val http = StaticProvider("HTTP", TtsResult.Success(happyPayload))
        val provider = CompositeTtsProvider.forMode(TtsProviderMode.LOCAL_PRIMARY, local, http)
        val result = provider.synthesizeToWav("hello")
        assertEquals(1, local.calls)
        assertEquals(1, http.calls, "fallback runs when primary fails")
        assertIs<TtsResult.Success>(result)
        assertEquals(8, (result as TtsResult.Success).wavBytes.size)
    }

    @Test fun `both fail and reason names both providers`() = runTest {
        val local = StaticProvider("Local", TtsResult.Failure("init failed"))
        val http = StaticProvider("HTTP", TtsResult.Failure("HTTP 401"))
        val provider = CompositeTtsProvider.forMode(TtsProviderMode.LOCAL_PRIMARY, local, http)
        val result = provider.synthesizeToWav("hello")
        assertIs<TtsResult.Failure>(result)
        // Surface both errors so the user can fix the right one. A
        // "primary only failed" message would hide the fallback bug.
        val reason = (result as TtsResult.Failure).reason
        assertTrue("Local" in reason, "expected Local mention in '$reason'")
        assertTrue("HTTP" in reason, "expected HTTP mention in '$reason'")
        assertTrue("init failed" in reason, "expected primary reason in '$reason'")
        assertTrue("HTTP 401" in reason, "expected fallback reason in '$reason'")
    }

    @Test fun `null http forces local-only even in CLOUD_PRIMARY`() = runTest {
        // Defensive: if the app passes http=null (e.g. no URL configured),
        // CLOUD_PRIMARY should NOT crash — it should degrade to local.
        val local = StaticProvider("Local", TtsResult.Success(happyPayload))
        val provider = CompositeTtsProvider.forMode(TtsProviderMode.CLOUD_PRIMARY, local, http = null)
        provider.synthesizeToWav("hello")
        assertEquals(1, local.calls)
    }
}
