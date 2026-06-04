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

/**
 * Flattens a list of PCM-16 chunks into a single ShortArray of
 * `totalSamples` length. Caller supplies the count so the result is
 * allocated once at the right size — `chunks.sumOf { it.size }` is
 * already known by every call site (each chunk gets counted on the
 * way in).
 *
 * Used by every recognizer that has to drain a [Flow]&lt;[ShortArray]&gt;
 * before processing it as a single buffer (Whisper inference, HTTP
 * upload, composite replay buffering).
 */
fun List<ShortArray>.flatten(totalSamples: Int): ShortArray {
    val out = ShortArray(totalSamples)
    var off = 0
    for (c in this) {
        c.copyInto(out, off)
        off += c.size
    }
    return out
}
