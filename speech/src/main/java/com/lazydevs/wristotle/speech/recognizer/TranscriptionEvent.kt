// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

/**
 * Stream of events a [Recognizer] emits during a session.
 *
 * These map onto the Android [android.speech.RecognitionService.Callback] surface,
 * but the recognizer doesn't depend on Android types so it can be unit-tested
 * without an instrumented environment.
 */
sealed interface TranscriptionEvent {
    /** Voice activity detected. Maps to Callback.beginningOfSpeech(). */
    data object SpeechStarted : TranscriptionEvent

    /** Interim transcript; may still change. Maps to Callback.partialResults(). */
    data class Partial(val text: String) : TranscriptionEvent

    /** Voice activity ended. Maps to Callback.endOfSpeech(). */
    data object SpeechEnded : TranscriptionEvent

    /** Terminal: final transcript. Maps to Callback.results(). */
    data class Final(val text: String, val confidence: Float = 0.5f) : TranscriptionEvent

    /**
     * Terminal: recognition failed.
     * [code] must be one of [android.speech.SpeechRecognizer]'s ERROR_* constants.
     */
    data class Error(val code: Int, val message: String? = null) : TranscriptionEvent
}