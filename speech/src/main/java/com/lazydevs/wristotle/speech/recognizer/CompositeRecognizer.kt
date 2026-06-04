package com.lazydevs.wristotle.speech.recognizer

import android.speech.SpeechRecognizer
import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.speech.audio.AudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "CompositeRecognizer"

/**
 * Two-stage recognizer with mode-aware failover. Drains the upstream
 * [AudioSource] once into an in-memory buffer, runs [primary] first,
 * and if it returns an [TranscriptionEvent.Error] re-runs [secondary]
 * over the same buffered audio.
 *
 * Used by the STT-provider feature to let the user choose between
 * local Whisper and an HTTP-backed cloud / self-hosted endpoint while
 * keeping one as a fallback when the other can't deliver a transcript.
 *
 * ## Why drain-first
 *
 * `MicAudioSource` and `PipeAudioSource` are pull-based — they don't
 * surface samples unless someone collects. If the primary recognizer
 * fails before collecting (e.g. Whisper's model-load error) the audio
 * is gone and the secondary would have nothing to transcribe. Draining
 * first into a [List]<[ShortArray]> means both recognizers see the
 * same utterance no matter where the primary fails.
 *
 * ## Speech-start/end emission
 *
 * Both [WhisperRecognizer][com.lazydevs.wristotle.speech.whisper.WhisperRecognizer]
 * and [HttpRecognizer] emit [TranscriptionEvent.SpeechStarted] /
 * [TranscriptionEvent.SpeechEnded] when they collect samples — but
 * if the composite ran both back-to-back, the downstream consumer
 * (Android's `RecognitionService.Callback`) would see
 * `beginningOfSpeech` / `endOfSpeech` twice. The composite emits
 * those once on its own and silently drops the inner recognizers'
 * copies.
 *
 * ## Failure surfacing
 *
 * When both recognizers fail, the composite surfaces the **primary's**
 * error — that's the user-chosen path, so its failure carries the
 * most useful signal ("my cloud is down" vs. "the fallback also
 * failed but I don't usually care"). The secondary's error is logged
 * but not emitted.
 *
 * Cancellation propagates: [requestAbort] forwards to both recognizers
 * so a long primary call doesn't block a cancel.
 */
class CompositeRecognizer(
    private val primary: Recognizer,
    private val secondary: Recognizer,
) : Recognizer {

    override fun transcribe(source: AudioSource): Flow<TranscriptionEvent> = flow {
        val buffered = ArrayList<ShortArray>()
        var totalSamples = 0
        var emittedStart = false

        try {
            source.samples().collect { chunk ->
                if (!emittedStart) {
                    emit(TranscriptionEvent.SpeechStarted)
                    emittedStart = true
                }
                buffered.add(chunk)
                totalSamples += chunk.size
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "audio source failed after $totalSamples samples", t)
            emit(TranscriptionEvent.Error(SpeechRecognizer.ERROR_AUDIO, "audio source failed: ${t.message}"))
            return@flow
        }

        if (totalSamples == 0) {
            emit(TranscriptionEvent.Error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "no audio received"))
            return@flow
        }

        emit(TranscriptionEvent.SpeechEnded)

        val primaryOutcome = runRecognizer(primary, buffered, source.sampleRate, source.channelCount)
        if (primaryOutcome is TranscriptionEvent.Final) {
            emit(primaryOutcome)
            return@flow
        }

        Log.d(TAG, "primary failed (${(primaryOutcome as? TranscriptionEvent.Error)?.message}); falling back to secondary")

        val secondaryOutcome = runRecognizer(secondary, buffered, source.sampleRate, source.channelCount)
        when (secondaryOutcome) {
            is TranscriptionEvent.Final -> emit(secondaryOutcome)
            is TranscriptionEvent.Error -> {
                Log.w(TAG, "both recognizers failed; surfacing primary's error")
                emit(primaryOutcome as TranscriptionEvent.Error)
            }
            else -> emit(primaryOutcome as TranscriptionEvent.Error)
        }
    }

    /**
     * Runs [recognizer] over a replay of the buffered audio and returns
     * its terminal event. Filters out the inner recognizer's
     * SpeechStarted / SpeechEnded — those are the composite's job.
     */
    private suspend fun runRecognizer(
        recognizer: Recognizer,
        buffered: List<ShortArray>,
        sampleRate: Int,
        channelCount: Int,
    ): TranscriptionEvent {
        var terminal: TranscriptionEvent? = null
        recognizer.transcribe(ReplayAudioSource(buffered, sampleRate, channelCount)).collect { event ->
            when (event) {
                is TranscriptionEvent.SpeechStarted,
                is TranscriptionEvent.SpeechEnded -> Unit
                is TranscriptionEvent.Final,
                is TranscriptionEvent.Error -> terminal = event
                is TranscriptionEvent.Partial -> Unit
            }
        }
        return terminal ?: TranscriptionEvent.Error(
            SpeechRecognizer.ERROR_CLIENT,
            "recognizer produced no terminal event",
        )
    }

    override fun requestAbort() {
        primary.requestAbort()
        secondary.requestAbort()
    }

    /** Closes both wrapped recognizers. WhisperRecognizer's close() is
     *  a no-op (the model handle is owned by the application cache),
     *  HttpRecognizer's close() is also a no-op — but propagate anyway
     *  so future recognizers that hold resources are cleaned up. */
    override fun close() {
        primary.close()
        secondary.close()
    }
}

/**
 * Cold AudioSource that replays a pre-recorded list of PCM chunks.
 * Used by [CompositeRecognizer] to feed the buffered audio into both
 * wrapped recognizers without re-reading the original (already-consumed)
 * AudioSource.
 *
 * No real-time pacing — chunks are emitted as fast as the collector
 * pulls. The downstream recognizer doesn't care about wall-clock arrival
 * order; only the byte sequence matters.
 */
internal class ReplayAudioSource(
    private val buffered: List<ShortArray>,
    override val sampleRate: Int,
    override val channelCount: Int,
) : AudioSource {

    override fun samples(): Flow<ShortArray> = flow {
        for (chunk in buffered) emit(chunk)
    }

    /** No-op — the buffer is finite; the [samples] flow naturally
     *  completes at the end. */
    override fun stop() = Unit
}
