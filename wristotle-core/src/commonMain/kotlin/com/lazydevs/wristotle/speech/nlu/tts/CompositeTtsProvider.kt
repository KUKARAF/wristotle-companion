// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import com.lazydevs.wristotle.speech.nlu.logging.Logger
import com.lazydevs.wristotle.speech.nlu.logging.NoopLogger
import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderMode

private const val TAG = "CompositeTtsProvider"

/**
 * Tries [primary] first and falls back to [fallback] when the primary
 * returns null. Mirrors `CompositeRecognizer`'s shape from the STT side
 * so the user sees a consistent "primary + fallback" UX in both
 * directions of the voice loop.
 *
 * Construction via [forMode] reads the user's [TtsProviderMode] and
 * wires the right primary/fallback combination.
 */
class CompositeTtsProvider(
    private val primary: TtsProvider,
    private val fallback: TtsProvider?,
    private val log: Logger = NoopLogger,
) : TtsProvider {

    override val displayName: String
        get() = if (fallback == null) primary.displayName
                else "${primary.displayName} → ${fallback.displayName}"

    override suspend fun synthesizeToWav(text: String): TtsResult {
        val first = primary.synthesizeToWav(text)
        if (first is TtsResult.Success) return first
        val firstFailure = first as TtsResult.Failure
        if (fallback == null) return firstFailure
        log.w(TAG, "${primary.displayName} failed (${firstFailure.reason}); trying ${fallback.displayName}")
        val second = fallback.synthesizeToWav(text)
        if (second is TtsResult.Success) return second
        val secondFailure = second as TtsResult.Failure
        return TtsResult.Failure(
            "${primary.displayName}: ${firstFailure.reason}; ${fallback.displayName}: ${secondFailure.reason}"
        )
    }

    companion object {
        /**
         * Wire a composite based on [mode]:
         *  - LOCAL_ONLY     → local only, no fallback
         *  - LOCAL_PRIMARY  → local first, HTTP fallback
         *  - CLOUD_PRIMARY  → HTTP first, local fallback
         */
        fun forMode(
            mode: TtsProviderMode,
            local: TtsProvider,
            http: TtsProvider?,
            log: Logger = NoopLogger,
        ): TtsProvider = when (mode) {
            TtsProviderMode.LOCAL_ONLY -> local
            TtsProviderMode.LOCAL_PRIMARY -> if (http == null) local else CompositeTtsProvider(local, http, log)
            TtsProviderMode.CLOUD_PRIMARY -> if (http == null) local else CompositeTtsProvider(http, local, log)
        }
    }
}
