package com.lazydevs.wristotle.speech.recognizer

import android.speech.SpeechRecognizer
import android.util.Log
import com.lazydevs.wristotle.speech.audio.AudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "StubRecognizer"

/**
 * Canned recognizer that returns a fixed transcript. Used in Phase 1 to verify
 * the SpeechRecognizer binding contract end-to-end before a real backend exists.
 *
 * Behaviour:
 * - 1st audio chunk: [TranscriptionEvent.SpeechStarted].
 * - Drains the entire audio source until it ends naturally (pipe EOF or external
 *   [AudioSource.stop]), then emits SpeechEnded + Final("hello world").
 *
 * Why drain instead of cap at N chunks: rePebble's TranscriptionProviderImpl has a
 * "streaming → submitted → awaiting recognition" state machine. If the recognizer
 * returns Final while rePebble is still streaming audio into the pipe, rePebble
 * marks the resulting DictationResult packet in a way the watch firmware rejects
 * with a non-success DictationSessionStatus. Whisper+ avoids this by running until
 * VAD detects ~800 ms of silence; the stub mirrors the wait-for-end behaviour by
 * draining the pipe to EOF.
 *
 * On error: [TranscriptionEvent.Error] with ERROR_AUDIO.
 * On no audio at all: ERROR_SPEECH_TIMEOUT.
 */
class StubRecognizer : Recognizer {

    override fun transcribe(source: AudioSource): Flow<TranscriptionEvent> = flow {
        Log.d(TAG, "transcribe start")
        var chunkCount = 0
        var emittedStart = false

        try {
            source.samples().collect { _ ->
                chunkCount++
                if (!emittedStart) {
                    Log.d(TAG, "emit SpeechStarted")
                    emit(TranscriptionEvent.SpeechStarted)
                    emittedStart = true
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "cancelled (chunks=$chunkCount)")
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "audio source failed (chunks=$chunkCount)", t)
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_AUDIO,
                "audio source failed: ${t.message}",
            ))
            return@flow
        }

        if (chunkCount == 0) {
            Log.w(TAG, "no audio received")
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                "no audio received",
            ))
            return@flow
        }

        Log.d(TAG, "emit SpeechEnded + Final (chunks=$chunkCount)")
        emit(TranscriptionEvent.SpeechEnded)
        emit(TranscriptionEvent.Final("hello world", confidence = 1.0f))
    }

    override fun close() = Unit
}
