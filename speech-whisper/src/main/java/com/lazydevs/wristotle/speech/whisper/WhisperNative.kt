package com.lazydevs.wristotle.speech.whisper

/**
 * JNI bridge into whisper.cpp.
 *
 * Lifecycle is one handle per loaded model: call [loadModel] to obtain a handle,
 * use [transcribe] zero or more times, then [freeModel] to release native memory.
 * Calling [transcribe] with a handle that has been freed is undefined behaviour
 * (the native side will read a dangling pointer); callers must not race.
 *
 * Threading: all functions are blocking and CPU-heavy. Call from a worker
 * dispatcher (e.g. `Dispatchers.Default` or a dedicated single-thread executor).
 * The native side has no internal serialisation — do not call [transcribe]
 * concurrently on the same handle.
 *
 * Errors: any failure (file not found, malformed model, etc.) is surfaced as
 * a `RuntimeException` thrown from the native call.
 */
internal object WhisperNative {

    init {
        System.loadLibrary("wristotle_speech")
    }

    /**
     * Loads a GGML Whisper model from disk.
     *
     * @param path absolute path to a `ggml-*.bin` file.
     * @return opaque handle to the loaded model. Pass to [transcribe] and [freeModel].
     * @throws RuntimeException if the file is missing, malformed, or out of memory.
     */
    external fun loadModel(path: String): Long

    /**
     * Returns the encoder hidden-state dimension of a loaded model:
     * tiny=384, base=512, small=768, medium=1024, large=1280. Used by the
     * recognizer to gate warm-up + skip the adaptive `audio_ctx`
     * truncation — large encoders need the full 1500-frame context to
     * produce correct output, so the truncation can't apply to them.
     */
    external fun nAudioState(handle: Long): Int

    /**
     * Transcribes PCM-16 mono audio at 16 kHz to text.
     *
     * @param handle returned by [loadModel].
     * @param samples raw 16-bit signed PCM, mono, 16 kHz sample rate. Converted
     *                to `float32` internally.
     * @param langCode ISO 639-1 language code (e.g. `"en"`). Empty or `null`
     *                 falls back to English.
     * @param nThreads number of CPU threads for inference. The caller picks the
     *                 right value for the device (see [defaultThreadCount]); values
     *                 ≤0 fall back to a built-in safe default.
     * @return concatenated transcript across all segments.
     * @throws RuntimeException on inference failure.
     */
    external fun transcribe(
        handle: Long,
        samples: ShortArray,
        langCode: String?,
        nThreads: Int,
        abortToken: Long,
    ): String

    /**
     * Allocates a cancel flag (native `std::atomic<bool>`, initially false) for
     * one recognizer. Pass the returned handle to [transcribe]; flip it with
     * [signalAbort] to abort an in-flight inference; release with [freeAbortToken].
     */
    external fun newAbortToken(): Long

    /** Requests abort of the inference currently using [token]. Thread-safe —
     *  called from a different thread than the running [transcribe]. */
    external fun signalAbort(token: Long)

    /** Frees an abort token. Must not race a [transcribe] using it. */
    external fun freeAbortToken(token: Long)

    /**
     * Picks an inference thread count appropriate for the current device.
     *
     * Heuristic: `availableProcessors() / 2 + 1`, clamped to `[2, 8]`. On a
     * heterogeneous ARM phone this biases toward the big/mid cores and leaves
     * room for the OS scheduler — adding little cores beyond that usually hurts
     * matmul-heavy workloads more than it helps. A quad-core phone gets 3
     * threads; an 8-core flagship gets 5; very wide chips cap at 8.
     */
    fun defaultThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2 + 1).coerceIn(2, 8)

    /**
     * Releases a model handle returned by [loadModel]. Safe to call with a
     * zero handle (no-op).
     */
    external fun freeModel(handle: Long)
}
