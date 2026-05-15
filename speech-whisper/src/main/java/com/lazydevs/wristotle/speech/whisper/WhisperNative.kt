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
     * Transcribes PCM-16 mono audio at 16 kHz to text.
     *
     * @param handle returned by [loadModel].
     * @param samples raw 16-bit signed PCM, mono, 16 kHz sample rate. Converted
     *                to `float32` internally.
     * @param langCode ISO 639-1 language code (e.g. `"en"`). Empty or `null`
     *                 falls back to English.
     * @return concatenated transcript across all segments.
     * @throws RuntimeException on inference failure.
     */
    external fun transcribe(handle: Long, samples: ShortArray, langCode: String?): String

    /**
     * Releases a model handle returned by [loadModel]. Safe to call with a
     * zero handle (no-op).
     */
    external fun freeModel(handle: Long)
}
