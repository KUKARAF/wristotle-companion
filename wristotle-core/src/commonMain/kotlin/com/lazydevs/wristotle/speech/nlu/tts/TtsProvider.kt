// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

/**
 * Platform-agnostic TTS synthesis surface. Implementations include the
 * Android `TextToSpeech` wrapper (local) and an OpenAI-compatible
 * `/audio/speech` HTTP client (remote). The watch-bound pipeline doesn't
 * care which produced the WAV — it just feeds the bytes through
 * [PebblePcmConverter] and ships the resulting 8 kHz / 8-bit PCM to the
 * watch speaker.
 *
 * Returns a typed [TtsResult] so Settings UX can surface the concrete
 * failure reason (HTTP status, init error) — a bare null would force
 * users to dig through logcat to understand "Test primary" failures.
 */
interface TtsProvider {
    /** Short human-readable label used in error messages and Settings UX. */
    val displayName: String

    /** Synthesize [text] into a WAV byte stream. */
    suspend fun synthesizeToWav(text: String): TtsResult
}

/**
 * Sealed result so callers can either branch on success (stream the WAV
 * to the watch) or surface the failure reason in the UI without digging
 * through logcat. The reason string is short and user-facing — e.g.
 * `"HTTP 401: incorrect_api_key"` or `"Android TTS init failed (status=-1)"`.
 */
sealed class TtsResult {
    data class Success(val wavBytes: ByteArray) : TtsResult() {
        // Compiler complains about the default ByteArray equals — we
        // never compare TtsResults, but explicit overrides silence it
        // and keep the data-class semantics consistent.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && wavBytes.contentEquals(other.wavBytes))
        override fun hashCode(): Int = wavBytes.contentHashCode()
    }
    data class Failure(val reason: String) : TtsResult()
}
