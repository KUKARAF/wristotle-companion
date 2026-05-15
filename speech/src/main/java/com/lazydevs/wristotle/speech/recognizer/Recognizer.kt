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
}
