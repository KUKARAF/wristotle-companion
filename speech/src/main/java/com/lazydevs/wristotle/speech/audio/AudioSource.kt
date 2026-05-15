package com.lazydevs.wristotle.speech.audio

import kotlinx.coroutines.flow.Flow

/**
 * Produces 16-bit signed PCM audio for a single recognition session.
 *
 * The returned [Flow] is cold: collecting starts capture/reading, cancelling the
 * collecting coroutine releases the underlying resource (mic handle or file descriptor).
 *
 * Implementations terminate the flow on EOF (pipe source) or when [stop] is called
 * by the consumer; never produce silence padding after a natural end.
 */
interface AudioSource {
    /** Samples per second. Whisper expects 16000. */
    val sampleRate: Int

    /** Channel count. Mono (1) for all current recognizers. */
    val channelCount: Int

    /** Emits raw 16-bit PCM samples in chunks (~100 ms each is typical). */
    fun samples(): Flow<ShortArray>

    /**
     * Request a graceful end-of-input. The [samples] flow completes normally shortly
     * after (no [TranscriptionEvent.Error] surfaced). Thread-safe and idempotent.
     *
     * The service calls this on `onStopListening` — the caller wants the final
     * transcript and is done feeding audio.
     */
    fun stop()
}
