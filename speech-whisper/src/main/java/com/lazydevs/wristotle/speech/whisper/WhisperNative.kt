package com.lazydevs.wristotle.speech.whisper

/**
 * JNI bridge into the native whisper.cpp library.
 *
 * Phase 2a: only `addTwo` exists — a sanity hello-world used to prove the
 * NDK/CMake/JNI pipeline builds and loads on-device. Phase 2b replaces this
 * with `loadModel(path)`, `transcribe(samples)`, and `freeModel()` backed by
 * whisper.cpp.
 *
 * Threading: the native functions are intended to be called from a worker
 * dispatcher (Dispatchers.Default or a dedicated single-thread executor),
 * never the main thread — inference is CPU-heavy.
 */
internal object WhisperNative {

    init {
        System.loadLibrary("wristotle_speech")
    }

    /** Phase 2a sanity check. Returns `a + b` computed in native code. */
    external fun addTwo(a: Int, b: Int): Int
}
