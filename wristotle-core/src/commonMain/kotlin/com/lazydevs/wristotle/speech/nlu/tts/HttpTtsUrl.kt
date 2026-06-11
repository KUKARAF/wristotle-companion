// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

/**
 * Resolve the OpenAI-compatible `/v1/audio/speech` endpoint from the
 * `Base URL` field on Settings → 🔊 Speech.
 *
 * Accepts either shape:
 * - `https://api.openai.com/v1` (bare base) → appends `/audio/speech`.
 * - `https://api.openai.com/v1/audio/speech` (the full endpoint copied
 *   straight out of the provider's docs) → used as-is.
 *
 * Trailing slashes are tolerated on both shapes. The full-endpoint
 * case matters because new users routinely paste the exact line from
 * the OpenAI / OpenedAI Speech / LiteLLM docs; double-appending would
 * silently 404 and look like a server failure.
 *
 * Lifted to commonMain as a pure function so the URL handling is
 * independently testable from commonTest — that's where the regression
 * risk lives (mishandling a trailing slash in a future tweak would
 * break every cloud-mode user).
 */
fun resolveSpeechEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return if (trimmed.endsWith("/audio/speech")) trimmed
           else "$trimmed/audio/speech"
}
