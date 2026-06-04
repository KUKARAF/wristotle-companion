package com.lazydevs.wristotle.speech.audio

import com.lazydevs.wristotle.logging.WristotleLog as Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "CapturingAudioSource"

/**
 * AudioSource decorator that buffers every emitted chunk and fires
 * [onCaptured] with the flattened PCM at the end of the session.
 *
 * Wraps any [AudioSource] (mic, pipe) so audio capture is decoupled
 * from the recognizer that consumes the source. Solves the double-fire
 * problem we'd otherwise hit when multiple recognizers run in sequence
 * inside [com.lazydevs.wristotle.speech.recognizer.CompositeRecognizer]:
 * the composite drains the inner source once into its own buffer and
 * replays from it, so [onCaptured] fires exactly once per dictation
 * regardless of which inner recognizer(s) eventually transcribed.
 *
 * The sink is invoked only when:
 *  - the underlying flow completed without throwing (so a cancelled
 *    session doesn't surprise-save a partial recording), and
 *  - at least one sample was captured.
 *
 * The capture itself is best-effort — a misbehaving sink (e.g. disk
 * full) can't break the recognition path. Everything is wrapped in
 * [runCatching] before propagating.
 */
class CapturingAudioSource(
    private val inner: AudioSource,
    private val onCaptured: (ShortArray) -> Unit,
) : AudioSource {

    override val sampleRate: Int get() = inner.sampleRate
    override val channelCount: Int get() = inner.channelCount

    override fun samples(): Flow<ShortArray> = flow {
        val buffer = ArrayList<ShortArray>()
        var totalSamples = 0
        var threw = false
        try {
            inner.samples().collect { chunk ->
                buffer.add(chunk)
                totalSamples += chunk.size
                emit(chunk)
            }
        } catch (t: Throwable) {
            threw = true
            throw t
        } finally {
            if (!threw && totalSamples > 0) {
                runCatching { onCaptured(buffer.flatten(totalSamples)) }
                    .onFailure { Log.w(TAG, "audio sink threw — ignoring", it) }
            }
        }
    }

    override fun stop() = inner.stop()
}
