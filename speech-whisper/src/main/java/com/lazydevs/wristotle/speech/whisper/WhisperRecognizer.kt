package com.lazydevs.wristotle.speech.whisper

import android.speech.SpeechRecognizer
import android.util.Log
import com.lazydevs.wristotle.speech.audio.AudioSource
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.TranscriptionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "WhisperRecognizer"

/**
 * Whisper-backed [Recognizer] that buffers an entire audio session into a
 * single PCM buffer, runs whisper.cpp on it via [WhisperNative], and emits the
 * transcript.
 *
 * Honors the Phase 1 drain-to-EOF contract: [TranscriptionEvent.Final] is
 * emitted only after [AudioSource.samples] has completed, so callers
 * (notably microPebble's TranscriptionProviderImpl) never see a result while
 * still streaming audio.
 *
 * Model loading is lazy on first [transcribe] call so app startup isn't
 * penalised. The handle is cached and intended to be reused across many
 * sessions. [close] releases the native handle; in practice the recognizer is
 * held by the Application and closed only when the user switches active model.
 *
 * Threading: [transcribe]'s flow body runs on whatever dispatcher the collector
 * uses (typically [kotlinx.coroutines.Dispatchers.Default] from the recognition
 * service). [WhisperNative.transcribe] is blocking CPU-heavy work — do not call
 * on the main thread. One concurrent [transcribe] per instance — the
 * recognition service enforces a single session.
 */
class WhisperRecognizer(
    val modelPath: String,
    private val language: String = "en",
) : Recognizer {

    private val loadLock = Any()

    @Volatile
    private var handle: Long = 0L

    override fun transcribe(source: AudioSource): Flow<TranscriptionEvent> = flow {
        val activeHandle = try {
            ensureLoaded()
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed", t)
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_CLIENT,
                "model load failed: ${t.message}",
            ))
            return@flow
        }

        val chunks = ArrayList<ShortArray>()
        var totalSamples = 0
        var emittedStart = false

        try {
            source.samples().collect { chunk ->
                if (!emittedStart) {
                    Log.d(TAG, "emit SpeechStarted")
                    emit(TranscriptionEvent.SpeechStarted)
                    emittedStart = true
                }
                chunks.add(chunk)
                totalSamples += chunk.size
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "cancelled after $totalSamples samples")
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "audio source failed after $totalSamples samples", t)
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_AUDIO,
                "audio source failed: ${t.message}",
            ))
            return@flow
        }

        if (totalSamples == 0) {
            Log.w(TAG, "no audio received")
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                "no audio received",
            ))
            return@flow
        }

        emit(TranscriptionEvent.SpeechEnded)

        // Flatten the chunk list into a single ShortArray for the JNI call.
        val flat = ShortArray(totalSamples)
        var offset = 0
        for (c in chunks) {
            c.copyInto(flat, offset)
            offset += c.size
        }

        val text = try {
            val threads = WhisperNative.defaultThreadCount()
            Log.d(TAG, "inference start: $totalSamples samples (~${totalSamples / 16_000.0}s), threads=$threads")
            WhisperNative.transcribe(activeHandle, flat, language, threads)
                .trim()
                .also { Log.d(TAG, "inference done: '$it'") }
        } catch (t: Throwable) {
            Log.e(TAG, "whisper_full failed", t)
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_CLIENT,
                "whisper inference failed: ${t.message}",
            ))
            return@flow
        }

        if (text.isEmpty()) {
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_NO_MATCH,
                "whisper returned empty transcript",
            ))
            return@flow
        }

        emit(TranscriptionEvent.Final(text))
    }

    /**
     * Per-session cleanup hook. Intentionally a no-op: this recognizer is
     * designed to be cached across many sessions and the loaded Whisper model
     * (~150-500 MB native memory) should survive between dictations to avoid
     * paying the ~600 ms reload cost each time. The owner — typically
     * `WristotleApplication` — calls [release] to actually free the model
     * when evicting from cache or on process teardown.
     */
    override fun close() = Unit

    /**
     * Actually frees the native model handle. Idempotent; safe to call
     * concurrently. After [release] the recognizer is unusable — create a new
     * instance for further transcriptions.
     */
    fun release() {
        val toFree = synchronized(loadLock) {
            val h = handle
            handle = 0L
            h
        }
        if (toFree != 0L) {
            Log.d(TAG, "releasing model handle $toFree")
            WhisperNative.freeModel(toFree)
        }
    }

    /** Double-checked load. Safe to call from any thread. */
    private fun ensureLoaded(): Long {
        handle.takeIf { it != 0L }?.let { return it }
        return synchronized(loadLock) {
            handle.takeIf { it != 0L }?.let { return@synchronized it }
            Log.d(TAG, "loading model: $modelPath")
            WhisperNative.loadModel(modelPath).also { handle = it }
        }
    }
}
