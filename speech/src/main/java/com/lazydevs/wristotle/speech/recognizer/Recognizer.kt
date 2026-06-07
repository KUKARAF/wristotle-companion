// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

import com.lazydevs.wristotle.speech.audio.AudioSource
import kotlinx.coroutines.flow.Flow

/**
 * Pluggable speech-to-text backend.
 *
 * One [Recognizer] instance handles one session: collect [transcribe] until the
 * flow completes with a [TranscriptionEvent.Final] or [TranscriptionEvent.Error],
 * then call [close] to release resources.
 *
 * Implementations:
 * - [StubRecognizer] — canned response, for verifying the SpeechRecognizer binding.
 * - WhisperRecognizer (in :speech-whisper, future) — runs whisper.cpp.
 */
interface Recognizer {
    fun transcribe(source: AudioSource): Flow<TranscriptionEvent>
    fun close()

    /**
     * Requests that an in-flight [transcribe] abort as soon as possible. Called
     * when the session is cancelled, so a blocking backend (e.g. whisper.cpp)
     * can bail mid-inference instead of running to completion. No-op by default
     * (the stub, or a backend that can't be interrupted, simply ignores it).
     */
    fun requestAbort() {}
}