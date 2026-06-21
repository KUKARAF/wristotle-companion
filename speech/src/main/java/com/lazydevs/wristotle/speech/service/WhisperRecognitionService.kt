// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.lazydevs.wristotle.speech.Recognizers
import com.lazydevs.wristotle.speech.AudioSinks
import com.lazydevs.wristotle.speech.audio.AudioSource
import com.lazydevs.wristotle.speech.audio.CapturingAudioSource
import com.lazydevs.wristotle.speech.audio.MicAudioSource
import com.lazydevs.wristotle.speech.audio.PipeAudioSource
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.TranscriptionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "WhisperRecognitionSvc"

/**
 * [android.speech.RecognitionService] implementation. Routes audio (mic or
 * [RecognizerIntent.EXTRA_AUDIO_SOURCE] pipe) through the [Recognizer] currently
 * registered with [Recognizers], translating [TranscriptionEvent]s into
 * [Callback] events.
 *
 * Lifecycle:
 * - One session at a time. Concurrent start → [SpeechRecognizer.ERROR_RECOGNIZER_BUSY].
 * - [onStopListening] → graceful end-of-input; recognizer flushes a final result.
 * - [onCancel] → hard abort; no callbacks emitted.
 * - [onDestroy] → recognizer.close() + scope cancellation.
 *
 * Threading: [onStartListening] is invoked on the main thread, so all real work
 * happens inside a coroutine on [scope]. Callbacks may be called from any thread;
 * the platform marshals to the original caller.
 */
class WhisperRecognitionService : RecognitionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var sessionJob: Job? = null
    @Volatile private var sessionSource: AudioSource? = null
    @Volatile private var recognizer: Recognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        Log.d(TAG, "onStartListening")
        if (sessionJob?.isActive == true) {
            Log.w(TAG, "session already active → BUSY")
            callback.safeError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        val sinceEmpty = System.currentTimeMillis() - lastEmptySessionEndMs
        if (sinceEmpty < RAPID_RETRY_WINDOW_MS) {
            Log.w(TAG, "rapid retry ${sinceEmpty}ms after a zero-audio session → BUSY " +
                "(upstream pipe-race suspected — see VoiceSessionManager.collectLatest in microPebble/libpebble3)")
            callback.safeError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            callback.safeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        val source = buildSource(intent)
        val recog = Recognizers.provider(this)
        sessionSource = source
        recognizer = recog
        sessionJob = scope.launch { runSession(source, recog, callback) }
    }

    override fun onStopListening(callback: Callback) {
        Log.d(TAG, "onStopListening")
        // Recognizer continues until its flow completes naturally, then emits Final.
        sessionSource?.stop()
    }

    override fun onCancel(callback: Callback) {
        Log.d(TAG, "onCancel")
        // Signal the (uninterruptible) native inference to bail FIRST, so it
        // stops grinding and frees the transcribe mutex for the next session;
        // cancelling the coroutine alone wouldn't interrupt the blocking JNI
        // call. See #93 (inference-queue contention).
        recognizer?.requestAbort()
        sessionJob?.cancel()
        sessionJob = null
        sessionSource?.stop()
        sessionSource = null
        recognizer?.close()
        recognizer = null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        scope.cancel()
        recognizer?.close()
        recognizer = null
        super.onDestroy()
    }

    private fun buildSource(intent: Intent): AudioSource {
        val raw: AudioSource = run {
            val pfd: ParcelFileDescriptor? = IntentCompat.getParcelableExtra(
                intent, EXTRA_AUDIO_SOURCE, ParcelFileDescriptor::class.java,
            )
            if (pfd != null) {
                val sampleRate = intent.getIntExtra(EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                val encoding = intent.getIntExtra(EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                val channels = intent.getIntExtra(EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                Log.d(TAG, "audio source = pipe (rate=$sampleRate ch=$channels enc=$encoding)")
                PipeAudioSource(pfd, sampleRate, channels, encoding)
            } else {
                Log.d(TAG, "audio source = mic")
                @SuppressLint("MissingPermission")  // checked above via hasRecordAudioPermission().
                MicAudioSource()
            }
        }
        // Optionally wrap so audio is captured regardless of which
        // Recognizer ultimately runs (Whisper / Http / Composite). The
        // sink fires once per session, when the source's flow naturally
        // completes — see CapturingAudioSource for the contract.
        val sink = AudioSinks.provider()
        return if (sink != null) CapturingAudioSource(raw, sink) else raw
    }

    private suspend fun runSession(source: AudioSource, recog: Recognizer, callback: Callback) {
        var sawSpeechStarted = false
        var sawTerminal = false // a Final transcript or an Error was delivered to the caller
        try {
            recog.transcribe(source).collect { event ->
                Log.d(TAG, "event: $event")
                when (event) {
                    TranscriptionEvent.SpeechStarted -> {
                        sawSpeechStarted = true
                        callback.safeBeginning()
                    }
                    is TranscriptionEvent.Partial -> callback.safePartial(event.text)
                    TranscriptionEvent.SpeechEnded -> callback.safeEndOfSpeech()
                    is TranscriptionEvent.Final -> {
                        sawTerminal = true
                        callback.safeResults(event.text)
                    }
                    is TranscriptionEvent.Error -> {
                        sawTerminal = true
                        callback.safeError(event.code)
                    }
                }
            }
            Log.d(TAG, "session collect done")
            // The recognizer completed without ever delivering a transcript or
            // an error. Send a precise terminal error so the watch can show a
            // meaningful message instead of a bare timeout — and crucially,
            // tell the two silent outcomes apart:
            //   - no audio ever arrived (pipe yielded zero bytes → no
            //     SpeechStarted): the dictation provider isn't delivering audio
            //     to us (e.g. voice/dictation not set up in the companion app).
            //     ERROR_AUDIO surfaces as the watch's "check dictation setup".
            //   - audio arrived but resolved to nothing: a genuine no-match →
            //     ERROR_NO_MATCH → the watch's "didn't catch that".
            if (!sawTerminal) {
                val code = if (!sawSpeechStarted) {
                    SpeechRecognizer.ERROR_AUDIO
                } else {
                    SpeechRecognizer.ERROR_NO_MATCH
                }
                Log.d(TAG, "no terminal event — synthesising error($code)")
                callback.safeError(code)
            }
        } catch (c: CancellationException) {
            Log.d(TAG, "session cancelled")
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "session failed", t)
            callback.safeError(SpeechRecognizer.ERROR_CLIENT)
        } finally {
            // Zero-audio outcome — pipe was empty before any frame
            // reached us. Arm the rapid-retry suppression so the
            // upstream cancellation cycle doesn't drag us through 3+
            // more failed sessions in the next 100 ms.
            if (!sawSpeechStarted) {
                lastEmptySessionEndMs = System.currentTimeMillis()
            }
            sessionJob = null
            sessionSource = null
        }
    }

    private fun hasRecordAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Callback methods can throw RemoteException if the caller died. Swallow + log so
    // a dead client never crashes the service process.
    private fun Callback.safeBeginning() = runCatching { beginningOfSpeech() }
        .onSuccess { Log.d(TAG, "→ beginningOfSpeech") }
        .onFailure { Log.w(TAG, "beginningOfSpeech failed", it) }

    private fun Callback.safeEndOfSpeech() = runCatching { endOfSpeech() }
        .onSuccess { Log.d(TAG, "→ endOfSpeech") }
        .onFailure { Log.w(TAG, "endOfSpeech failed", it) }

    private fun Callback.safeError(code: Int) = runCatching { error(code) }
        .onSuccess { Log.d(TAG, "→ error($code)") }
        .onFailure { Log.w(TAG, "error callback failed", it) }

    private fun Callback.safePartial(text: String) = runCatching {
        partialResults(buildResultsBundle(text))
    }.onSuccess { Log.d(TAG, "→ partialResults('$text')") }
        .onFailure { Log.w(TAG, "partialResults failed", it) }

    private fun Callback.safeResults(text: String) = runCatching {
        results(buildResultsBundle(text))
    }.onSuccess { Log.d(TAG, "→ results('$text')") }
        .onFailure { Log.w(TAG, "results failed", it) }

    // CONFIDENCE_SCORES intentionally omitted. rePebble's TranscriptionProviderImpl
    // expects per-word entries in its DictationResult packet; if we send a single
    // confidence for a multi-word transcript it produces a malformed packet that the
    // watch firmware rejects with a non-success DictationSessionStatus. Mirrors the
    // pattern used by whisperIMEplus, which is known to work with rePebble dictation.
    private fun buildResultsBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private companion object {
        // RecognizerIntent.EXTRA_AUDIO_SOURCE_* are API 33+ constants. Inlined here
        // as plain strings so compile and lint stay quiet on minSdk 24 — when an
        // older caller doesn't put these extras, our defaults kick in and we fall
        // back to the mic.
        const val EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"
        const val EXTRA_AUDIO_SOURCE_SAMPLING_RATE =
            "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"
        const val EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"
        const val EXTRA_AUDIO_SOURCE_CHANNEL_COUNT =
            "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"

        /**
         * Wall-clock when the most recent session ended *without* ever
         * emitting `SpeechStarted` — i.e. the pipe yielded zero bytes.
         * Used to suppress rapid-fire `onStartListening` retries that
         * upstream callers like microPebble's `TranscriptionProviderImpl`
         * generate when their `VoiceSessionManager.collectLatest` cancels
         * a setup mid-flight and re-issues it: the EBADF + retry storm
         * has no chance of producing audio (the pipe FDs were already
         * yanked by the upstream cancellation), and accepting it just
         * pollutes logs + wastes a recognizer launch.
         *
         * MUST be in the companion (process-level) — Android destroys
         * + recreates the Service instance between sessions, so an
         * instance field would reset to 0 on every retry, defeating
         * the cooldown.
         */
        @Volatile var lastEmptySessionEndMs: Long = 0L

        /** See [lastEmptySessionEndMs]. 500 ms comfortably covers
         *  microPebble's collectLatest cancel→reschedule window
         *  (~100 ms observed) without blocking legitimate quick
         *  back-to-back dictations (a human can't realistically press
         *  select twice within 500 ms). */
        const val RAPID_RETRY_WINDOW_MS = 500L
    }
}