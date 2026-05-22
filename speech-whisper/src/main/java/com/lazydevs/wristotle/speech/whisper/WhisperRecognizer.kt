package com.lazydevs.wristotle.speech.whisper

import android.speech.SpeechRecognizer
import com.lazydevs.wristotle.logging.WristotleLog as Log
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
    /**
     * Optional sink for the raw PCM buffer that just went into Whisper.
     * When set, the recognizer hands the samples (16 kHz mono PCM-16) to
     * the lambda after every successful capture so a downstream owner can
     * persist them for replay (e.g. the conversation-audio store).
     * Failures swallow inside the sink — the recognition path is never
     * affected.
     *
     * Null disables capture — zero overhead.
     */
    private val audioSink: ((ShortArray) -> Unit)? = null,
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

    /** True once [warmUp] has primed the compute graph (idempotency guard). */
    @Volatile
    private var warmed: Boolean = false

    /**
     * Native cancel flag passed into every [WhisperNative.transcribe]. [requestAbort]
     * flips it so a cancelled session's blocking inference bails immediately and
     * frees [transcribeMutex] for the next dictation, instead of running to
     * completion (see #93). Freed alongside the model handle.
     */
    private val abortToken: Long = WhisperNative.newAbortToken()

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

        // Hand the PCM buffer to the optional sink. Wrapped in runCatching so
        // a misbehaving sink can't break the recognition path.
        audioSink?.let { sink ->
            runCatching { sink(flat) }
                .onFailure { Log.w(TAG, "audioSink threw — ignoring", it) }
        }

        val rawText = try {
            val threads = WhisperNative.defaultThreadCount()
            Log.d(TAG, "inference start: $totalSamples samples (~${totalSamples / 16_000.0}s), threads=$threads")
            transcribeMutex.withLock {
                WhisperNative.transcribe(activeHandle, flat, language, threads, abortToken)
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

        // Whisper greedy + single_segment hallucinates phrase loops on
        // short or trailing-silence audio. Collapse them before downstream
        // consumers (NLU classifier, slot extractors, watch chat) see the
        // duplicated mess. No-op when the transcript is already clean.
        val deduped = dedupeRepeatedPhrases(rawText).also {
            if (it != rawText) Log.d(TAG, "deduped: '$it'")
        }
        // Drop subtitle-annotation-only outputs (`*Door opens*`,
        // `[laughter]`, `(silence)`) — Whisper emits these on silence
        // because its training data includes subtitle files. Letting
        // them through would surface in the watch chat as "Unknown
        // command: *DING*".
        val text = stripAnnotationOnly(deduped).also {
            if (it.isEmpty() && deduped.isNotEmpty()) {
                Log.d(TAG, "annotation-only transcript dropped: '$deduped'")
            }
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
     * Loads the model and runs one throwaway inference on a second of silence,
     * so the first *real* dictation doesn't pay whisper.cpp's cold-start cost.
     * That cost is the first `whisper_full` call — compute-graph allocation +
     * paging in the mmap'd weights — not the model load itself (observed ~20s+
     * on a large model, which blows the watch's dictation timeout). Priming it
     * ahead of time makes the first dictation as fast as a warm one.
     *
     * Idempotent. Bypasses [audioSink] (no spurious capture). Holds
     * [transcribeMutex] for the priming call, so if a real dictation lands
     * mid-warm-up it simply queues — fine, since warm-up runs at startup well
     * before any dictation. Call off the main thread.
     */
    suspend fun warmUp() {
        if (warmed) return
        val activeHandle = try {
            acquireHandle()
        } catch (t: Throwable) {
            Log.w(TAG, "warm-up: model load failed", t)
            return
        }
        try {
            val silence = ShortArray(16_000) // 1s @ 16 kHz mono
            transcribeMutex.withLock {
                WhisperNative.transcribe(activeHandle, silence, language, WhisperNative.defaultThreadCount(), abortToken)
            }
            warmed = true
            Log.d(TAG, "warm-up complete — first dictation will be warm")
        } catch (t: Throwable) {
            Log.w(TAG, "warm-up inference failed", t)
        } finally {
            finishInFlight()
        }
    }

    /**
     * Signals the native abort flag so an in-flight [transcribe] (or [warmUp])
     * bails at the next whisper.cpp abort-callback poll, releasing the mutex
     * for the next session. Safe to call from any thread / when idle (the flag
     * is reset at the start of each transcribe).
     */
    override fun requestAbort() {
        WhisperNative.signalAbort(abortToken)
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
            WhisperNative.freeAbortToken(abortToken)
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
            WhisperNative.freeAbortToken(abortToken)
        }
    }
}
