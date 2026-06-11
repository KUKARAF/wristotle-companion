// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [resolveSpeechEndpoint]. The smart URL append exists
 * because users routinely paste the full `…/v1/audio/speech` URL from
 * the OpenAI docs — without this logic we'd double-append and silently
 * 404 every cloud-TTS request.
 */
class HttpTtsUrlTest {

    @Test fun `bare v1 base gets audio speech appended`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            resolveSpeechEndpoint("https://api.openai.com/v1"),
        )
    }

    @Test fun `bare v1 base with trailing slash gets audio speech appended cleanly`() {
        // Trailing slash on bare base — common in env vars / copy-paste.
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            resolveSpeechEndpoint("https://api.openai.com/v1/"),
        )
    }

    @Test fun `full endpoint is passed through unchanged`() {
        // User pastes the exact line from the provider's docs.
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            resolveSpeechEndpoint("https://api.openai.com/v1/audio/speech"),
        )
    }

    @Test fun `full endpoint with trailing slash is trimmed but not doubled`() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            resolveSpeechEndpoint("https://api.openai.com/v1/audio/speech/"),
        )
    }

    @Test fun `self-hosted shapes work the same`() {
        // OpenedAI Speech, LiteLLM, Piper proxy, etc. — same OpenAI
        // shape, different host. URL handling should be host-agnostic.
        assertEquals(
            "http://tts.local:8000/v1/audio/speech",
            resolveSpeechEndpoint("http://tts.local:8000/v1"),
        )
        assertEquals(
            "http://tts.local:8000/v1/audio/speech",
            resolveSpeechEndpoint("http://tts.local:8000/v1/audio/speech"),
        )
    }

    @Test fun `unusual path that ends with audio speech is left alone`() {
        // Edge case: user has a proxy that re-mounts the endpoint at
        // a non-standard prefix but still terminates in /audio/speech.
        // Treat it as a full endpoint.
        assertEquals(
            "https://gateway.example.com/api/llm/v1/audio/speech",
            resolveSpeechEndpoint("https://gateway.example.com/api/llm/v1/audio/speech"),
        )
    }
}
