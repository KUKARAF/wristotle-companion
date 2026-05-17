package com.lazydevs.wristotle.speech.whisper

import android.speech.SpeechRecognizer
import android.util.Log
import com.lazydevs.wristotle.speech.audio.AudioSource
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.TranscriptionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Guards [handle], [inFlight], and [releaseDeferred] as a single state
     * machine. Synchronous, non-coroutine — held only briefly for state
     * mutation, never across the long native inference call.
     */
    private val nativeLock = Any()

    /**
     * Serializes the JNI [WhisperNative.transcribe] call so two concurrent
     * sessions don't end up running on the same `whisper_context` handle.
     *
     * The recognizer is cached per-model in [com.lazydevs.wristotle.WristotleApplication]
     * and reused across sessions. The recognition service cancels its session
     * coroutine on [WhisperRecognitionService.onCancel], but a blocking JNI
     * call can't be cancelled and runs to completion regardless — so a new
     * `onStartListening` right after a cancel can launch a second transcribe
     * on the same handle. `whisper_context` isn't thread-safe; we observed
     * 30× slowdown (cache thrashing) plus the previous session's transcript
     * leaking into the next 0.26 s session. Mutex queues the second call
     * until the first returns. UX cost is small — whisper is single-threaded
     * per context, so the second call would have been blocked at the kernel
     * level anyway, just at a layer where it caused corruption.
     */
    private val transcribeMutex = Mutex()

    /** The native whisper_context pointer (cast to Long), 0L until loaded. */
    @Volatile
    private var handle: Long = 0L

    /** True while a [transcribe] is between acquiring the handle and finishing the native call. */
    private var inFlight: Boolean = false

    /**
     * Set by [release] when the caller wants the model freed but a [transcribe]
     * is currently using the handle. The transcribe's `finally` performs the
     * actual free after the native call returns, preventing a use-after-free.
     */
    private var releaseDeferred: Boolean = false

    override fun transcribe(source: AudioSource): Flow<TranscriptionEvent> = flow {
        val activeHandle = try {
            acquireHandle()
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
            transcribeMutex.withLock {
                WhisperNative.transcribe(activeHandle, flat, language, threads)
            }
                .trim()
                .also { Log.d(TAG, "inference done: '$it'") }
        } catch (t: Throwable) {
            Log.e(TAG, "whisper_full failed", t)
            emit(TranscriptionEvent.Error(
                SpeechRecognizer.ERROR_CLIENT,
                "whisper inference failed: ${t.message}",
            ))
            return@flow
        } finally {
            finishInFlight()
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
     * Frees the native model handle. If a transcribe is still mid-flight (rare
     * but possible during a model switch), the free is deferred to the
     * transcribe's `finally` to prevent a use-after-free. Idempotent.
     */
    fun release() {
        val toFree = synchronized(nativeLock) {
            if (inFlight) {
                releaseDeferred = true
                Log.d(TAG, "release deferred — transcribe in flight on handle $handle")
                return@synchronized 0L
            }
            val h = handle
            handle = 0L
            h
        }
        if (toFree != 0L) {
            Log.d(TAG, "releasing model handle $toFree")
            WhisperNative.freeModel(toFree)
        }
    }

    /**
     * Loads the model if needed and marks the recognizer as in-flight. Holds
     * the lock only across the load itself — the native call runs unlocked
     * after this returns, so [release] can be invoked from another thread
     * during transcribe without blocking it.
     *
     * Throws if [release] has already been called (recognizer is dead).
     */
    private fun acquireHandle(): Long = synchronized(nativeLock) {
        check(!releaseDeferred) { "WhisperRecognizer released" }
        if (handle == 0L) {
            Log.d(TAG, "loading model: $modelPath")
            handle = WhisperNative.loadModel(modelPath)
        }
        inFlight = true
        handle
    }

    /**
     * Clears the in-flight flag at the end of a transcribe. If [release] was
     * called during the native call, performs the deferred free now.
     */
    private fun finishInFlight() {
        val toFree = synchronized(nativeLock) {
            inFlight = false
            if (releaseDeferred && handle != 0L) {
                val h = handle
                handle = 0L
                h
            } else 0L
        }
        if (toFree != 0L) {
            Log.d(TAG, "performing deferred release of handle $toFree")
            WhisperNative.freeModel(toFree)
        }
    }
}
