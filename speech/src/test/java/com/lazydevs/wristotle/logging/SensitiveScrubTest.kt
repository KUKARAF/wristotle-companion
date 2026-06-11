// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guard test for [SensitiveScrub]. Each provider Wristotle supports has
 * a row here so a future contributor who adds a new key shape can't
 * regress the standing "no API keys in logs" rule — adding a new
 * shape without updating this test will fail CI.
 *
 * See `feedback_no_api_keys_in_logs` in memory for the rule.
 */
class SensitiveScrubTest {

    @Test fun `redacts Authorization Bearer header`() {
        val redacted = SensitiveScrub.redact("Authorization: Bearer sk-proj-abc123def456ghi789jkl")
        assertFalse("raw key leaked: $redacted", redacted.contains("sk-proj-abc123"))
        assertEquals("Authorization: Bearer <redacted>", redacted)
    }

    @Test fun `redacts Authorization Bearer lowercase`() {
        val redacted = SensitiveScrub.redact("authorization: bearer abc123def456ghi789jkl0")
        assertFalse(redacted.contains("abc123def456"))
    }

    @Test fun `redacts plain Bearer-less Authorization value`() {
        val redacted = SensitiveScrub.redact("Authorization: some-opaque-token-value")
        assertEquals("Authorization: <redacted>", redacted)
    }

    @Test fun `redacts X-Api-Key header`() {
        val redacted = SensitiveScrub.redact("X-API-Key: 8c3f2e9d-aaaa-bbbb-cccc-ddddeeeeffff")
        assertFalse(redacted.contains("8c3f2e9d"))
        assertEquals("X-API-Key: <redacted>", redacted)
    }

    @Test fun `redacts Anthropic-API-Key header`() {
        val redacted = SensitiveScrub.redact("anthropic-api-key: sk-ant-api03-supersecret-value-here-12345")
        assertFalse(redacted.contains("sk-ant"))
        assertFalse(redacted.contains("supersecret"))
    }

    @Test fun `redacts api_key query param`() {
        val redacted = SensitiveScrub.redact("GET https://api.example.com/v1/data?api_key=hunter2hunter2hunter2&lat=10")
        assertFalse(redacted.contains("hunter2"))
        // Non-secret params survive — we don't want to break observability.
        assertEquals(
            "GET https://api.example.com/v1/data?api_key=<redacted>&lat=10",
            redacted,
        )
    }

    @Test fun `redacts OpenWeather appid query param`() {
        val redacted = SensitiveScrub.redact("https://api.openweathermap.org/?q=Tokyo&appid=abcd1234deadbeef")
        assertFalse(redacted.contains("abcd1234"))
    }

    @Test fun `redacts bare sk- standalone key`() {
        val redacted = SensitiveScrub.redact("token=sk-proj-aLongOpaqueStringWithManyChars1234")
        assertFalse(redacted.contains("sk-proj-aLongOpaque"))
    }

    @Test fun `redacts sk-ant- Anthropic standalone key`() {
        val redacted = SensitiveScrub.redact("dumped header: sk-ant-api03-AAAAAAAAAAAAAAAAAAAA-BBBBBB")
        assertFalse(redacted.contains("sk-ant"))
    }

    @Test fun `redacts sk-or- OpenRouter standalone key`() {
        val redacted = SensitiveScrub.redact("Header looks like: sk-or-v1-abcdef0123456789abcdef")
        assertFalse(redacted.contains("sk-or-v1"))
    }

    @Test fun `redacts URL with embedded user-pass credentials`() {
        val redacted = SensitiveScrub.redact("connect failed: https://admin:s3cret!@10.0.0.5/v1/audio")
        assertFalse("user leaked: $redacted", redacted.contains("admin"))
        assertFalse("pass leaked: $redacted", redacted.contains("s3cret"))
        assertEquals("connect failed: https://<redacted>:<redacted>@10.0.0.5/v1/audio", redacted)
    }

    @Test fun `redacts password query param`() {
        val redacted = SensitiveScrub.redact("retry url: http://host/path?user=joe&password=topsecretvalue")
        assertFalse(redacted.contains("topsecret"))
    }

    @Test fun `redacts token query param`() {
        val redacted = SensitiveScrub.redact("retry url: http://host?token=abcdefghijklmnop")
        assertFalse(redacted.contains("abcdefgh"))
    }

    @Test fun `redacts inside multi-line stack trace`() {
        val trace = """
            java.io.IOException: HTTP 401 for https://api.example.com/v1/x?api_key=hunter2hunter2
                at com.example.HttpClient.send(HttpClient.kt:42)
                Caused by: java.net.ConnectException: Authorization: Bearer sk-leakedkey1234567890abc
        """.trimIndent()
        val redacted = SensitiveScrub.redact(trace)
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("sk-leakedkey"))
    }

    @Test fun `preserves non-secret content unchanged`() {
        val original = "GET /v1/audio/speech 200 OK in 1234ms (model=tts-1, voice=alloy)"
        assertEquals(original, SensitiveScrub.redact(original))
    }

    @Test fun `empty string short-circuits`() {
        assertEquals("", SensitiveScrub.redact(""))
    }

    @Test fun `does not redact legitimate Authorization mention without value`() {
        // "Authorization header" alone (no value) shouldn't get mangled —
        // we only redact when there's a token after the colon-or-equals.
        val original = "request lacked Authorization header"
        assertEquals(original, SensitiveScrub.redact(original))
    }
}
