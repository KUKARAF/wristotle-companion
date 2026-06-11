// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.lazydevs.wristotle.logging.WristotleLogger
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import com.lazydevs.wristotle.speech.nlu.tts.PebblePcmConverter
import com.lazydevs.wristotle.speech.nlu.tts.TtsProvider
import com.lazydevs.wristotle.speech.nlu.tts.TtsResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "TtsStreamer"

/**
 * Path B: ask the configured [TtsProvider] for a WAV, run it through
 * [PebblePcmConverter] (DSP pipeline → 8 kHz signed 8-bit mono PCM), and
 * ship the PCM to the watch in chunked AppMessages via
 * [WatchTransport.sendTtsChunk]. The watch's `tts/tts.c` module buffers
 * and drains it to `speaker_stream_write`.
 */
class TtsStreamer(
    private val transport: WatchTransport,
    private val provider: TtsProvider,
) {

    /**
     * Synthesize [text] → PCM → chunked send. Splits [text] on sentence
     * boundaries and runs synthesis for later sentences IN PARALLEL with
     * playback of earlier ones, so time-to-first-audio is bounded by the
     * first sentence's synth time, not the whole phrase's.
     *
     * Streaming pacing state is shared across sentences so playback is
     * gapless — the watch sees the concatenated PCM as one stream.
     * Returns `null` on success, a short reason on failure.
     *
     * Always ensures the watch's TTS state machine is closed before
     * returning — wraps the whole pipeline in a try/finally that sends
     * a terminal `tts_end=true` inside `NonCancellable`. On the success
     * path that's a duplicate of the end-flag carried by the last audio
     * chunk; the watch's `prv_handle_end` is idempotent (a second close
     * on an already-IDLE/FINISHING stream is a no-op), so the duplicate
     * is safe and the failure paths get clean teardown for free.
     * Centralising the cleanup here means new callers don't have to
     * remember the NonCancellable wrap — the only thing a caller is
     * responsible for is the pre-warm `tts_start` (which has to happen
     * BEFORE speak so the watch is in BUFFERING when synth finishes).
     */
    suspend fun speak(text: String, chunkBytes: Int = DEFAULT_CHUNK_BYTES): String? = try {
        coroutineScope {
            val speakStartMs = System.currentTimeMillis()
            val sentences = splitIntoSentences(text)
            if (sentences.isEmpty()) return@coroutineScope "empty text"

            // Foreground synth for sentence 0 — needed before we can stream
            // anything.
            val first = synthesizeToPcm(sentences[0])
            val firstPcm = first.pcm ?: return@coroutineScope first.failureReason
            Log.i(TAG, "tts: +${System.currentTimeMillis() - speakStartMs}ms sentence 0 synth done (${firstPcm.size}B)")

            // Pipelined-sequential synthesis: kick off sentence N+1's synth
            // RIGHT BEFORE we start streaming sentence N, so the next synth
            // overlaps with the current stream's playback time. Only one
            // synth is in flight at any moment — required because the
            // Android `TextToSpeech` engine can't service concurrent
            // synthesizeToFile calls on the same provider instance (the
            // listener is per-instance, so requests collide and all but
            // one return empty WAVs).
            val cs: CoroutineScope = this
            fun synthAsync(i: Int): Deferred<SynthResult> =
                cs.async(Dispatchers.Default) { synthesizeToPcm(sentences[i]) }

            var nextSynth: Deferred<SynthResult>? =
                if (sentences.size > 1) synthAsync(1) else null
            Log.i(TAG, "speak(${sentences.size} sentences): sentence 0 ready ${firstPcm.size}B" +
                (if (nextSynth != null) ", sentence 1 synth started" else ""))

            val state = StreamState()
            if (!streamPcmContinuation(firstPcm, chunkBytes, state, isFirst = true, isLast = sentences.size == 1)) {
                return@coroutineScope "watch send failed (BLE disconnected?)"
            }

            for (i in 1 until sentences.size) {
                val result = nextSynth!!.await()
                // Pre-kick the FOLLOWING synth before streaming this one so
                // it runs in parallel with playback rather than after.
                nextSynth = if (i + 1 < sentences.size) synthAsync(i + 1) else null
                val pcm = result.pcm
                if (pcm == null) {
                    Log.w(TAG, "sentence $i synth failed: ${result.failureReason}")
                    continue
                }
                val isLast = i == sentences.size - 1
                if (!streamPcmContinuation(pcm, chunkBytes, state, isFirst = false, isLast = isLast)) {
                    return@coroutineScope "watch send failed mid-stream"
                }
            }
            null
        }
    } finally {
        // Idempotent terminal close — on success this duplicates the
        // end-flag carried by the last audio chunk (watch-side
        // prv_handle_end no-ops on already-IDLE/FINISHING). On failure
        // (synth fail, BLE drop, coroutine cancellation) it's the only
        // close the watch sees, so the state machine doesn't strand
        // BUFFERING with tts_is_active() stuck true.
        withContext(NonCancellable) {
            runCatching {
                transport.sendTtsChunk(ByteArray(0), start = false, end = true)
            }
        }
    }

    private data class SynthResult(val pcm: ByteArray?, val failureReason: String?)

    /** Provider call + DSP pipeline → 8 kHz s8 PCM. */
    private suspend fun synthesizeToPcm(text: String): SynthResult =
        when (val synth = provider.synthesizeToWav(text)) {
            is TtsResult.Failure -> SynthResult(null, synth.reason)
            is TtsResult.Success -> {
                val decoded = PebblePcmConverter.decodeWavToMonoS16(synth.wavBytes, WristotleLogger)
                if (decoded == null) {
                    SynthResult(null, "WAV decode failed (${synth.wavBytes.size} B; wrong response_format?)")
                } else {
                    val (s16, srcRate) = decoded
                    SynthResult(PebblePcmConverter.convert(s16, srcRate, WristotleLogger), null)
                }
            }
        }

    /**
     * Sentence splitter. Keeps the terminating punctuation with the sentence
     * so the TTS engine still hears "Hi." instead of "Hi". Decimals + times
     * are unaffected because they lack the trailing space.
     *
     * Newlines also delimit — morning-brief style replies use one section
     * per line with no terminating period, and without this they'd come
     * through as a single multi-line "sentence" and burn the whole synth
     * before audio could start.
     *
     * Additionally, if the first sentence after the standard split is long
     * and has a comma, split off a leading clause so time-to-first-audio
     * drops to one short synth instead of waiting for the whole long
     * sentence. Trades a small intonation seam at the first comma for
     * 2-4 seconds of perceived latency. Only applies to the FIRST
     * sentence — subsequent sentences pipeline behind playback so their
     * synth time is hidden.
     */
    private fun splitIntoSentences(text: String): List<String> =
        com.lazydevs.wristotle.speech.nlu.tts.splitTtsIntoSentences(text)

    // ── Spike-card diagnostics ─────────────────────────────────────────

    suspend fun playTestTone(seconds: Double = 3.0, chunkBytes: Int = DEFAULT_CHUNK_BYTES): Boolean {
        val pcm = makeSineWave(seconds, 440.0)
        Log.i(TAG, "test tone: 440Hz, ${seconds}s, ${pcm.size} bytes")
        return streamChunks(pcm, chunkBytes)
    }

    /** Synthesize via the configured provider, play through phone AudioTrack.
     *  Lets us A/B watch playback against what the source PCM sounds like
     *  at this sample rate / bit depth, independent of the BLE / watch
     *  speaker path. */
    suspend fun speakOnPhone(text: String): Boolean {
        val synth = provider.synthesizeToWav(text) as? TtsResult.Success ?: return false
        val (s16, srcRate) = PebblePcmConverter.decodeWavToMonoS16(synth.wavBytes, WristotleLogger)
            ?: return false
        val pcm = PebblePcmConverter.convert(s16, srcRate, WristotleLogger)
        playPcmOnPhone(pcm)
        return true
    }

    fun playTonePhone(seconds: Double = 3.0) {
        playPcmOnPhone(makeSineWave(seconds, 440.0))
    }

    private fun makeSineWave(seconds: Double, freq: Double): ByteArray {
        val numSamples = (seconds * 8000).toInt()
        val pcm = ByteArray(numSamples)
        var rng = 0x1234L
        for (i in 0 until numSamples) {
            val s = kotlin.math.sin(2.0 * kotlin.math.PI * freq * i / 8000.0)
            val u1 = ((rng ushr 11) and ((1L shl 53) - 1)).toDouble() / (1L shl 53).toDouble()
            rng = rng * 6364136223846793005L + 1442695040888963407L
            val u2 = ((rng ushr 11) and ((1L shl 53) - 1)).toDouble() / (1L shl 53).toDouble()
            rng = rng * 6364136223846793005L + 1442695040888963407L
            val dither = u1 - u2
            pcm[i] = (s * 100.0 + dither).toInt().toByte()
        }
        return pcm
    }

    private fun playPcmOnPhone(s8Pcm: ByteArray) {
        val s16 = ShortArray(s8Pcm.size)
        for (i in s8Pcm.indices) s16[i] = (s8Pcm[i].toInt() shl 8).toShort()
        val sampleRate = 8000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bytesNeeded = s16.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, bytesNeeded))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(s16, 0, s16.size)
        track.play()
        Log.i(TAG, "playing ${s8Pcm.size} bytes through phone AudioTrack")
        // Daemon so it doesn't keep the JVM alive if the app process is
        // shutting down mid-playback.
        Thread {
            try { Thread.sleep((s8Pcm.size * 1000L / sampleRate) + 200L) } catch (_: InterruptedException) {}
            track.stop()
            track.release()
        }.apply { isDaemon = true; name = "TtsAudioTrackRelease" }.start()
    }

    // ── Streaming ──────────────────────────────────────────────────────

    /** Shared pacing state across multiple `streamPcmContinuation` calls.
     *  Tracked in terms of ring-usage rather than wall-vs-expected-audio
     *  so the between-sentence await gap doesn't trick the pacing into
     *  burst-catching-up (which overflows the 12 KB watch ring and drops
     *  bytes silently). */
    private class StreamState {
        var startMs: Long = 0L              // first chunk wall time
        var totalSent: Int = 0              // bytes shipped, all sentences
        var hasStarted: Boolean = false
        var speakerStartMs: Long = 0L       // wall time prebuffer was met
        var hasPrimed: Boolean = false
    }

    /**
     * Send [pcm] as a continuation of an ongoing watch stream. Pacing is
     * keyed off [state]'s cumulative totals so back-to-back sentences play
     * gaplessly: the watch sees one virtually-uninterrupted PCM stream.
     *
     * `tts_start=1` and `tts_end=1` flags are gated by [isFirst]/[isLast]
     * AT THE PER-STREAM level — not per-sentence — so the watch's state
     * machine sees a single BUFFERING → PLAYING → FINISHING cycle across
     * the whole speech.
     */
    private suspend fun streamPcmContinuation(
        pcm: ByteArray,
        chunkBytes: Int,
        state: StreamState,
        isFirst: Boolean,
        isLast: Boolean,
    ): Boolean {
        if (!state.hasStarted) {
            state.startMs = System.currentTimeMillis()
            state.hasStarted = true
        }
        var offset = 0
        var chunkIdx = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunkBytes, pcm.size)
            val chunkSize = end - offset
            val chunk = pcm.copyOfRange(offset, end)
            val isFirstChunkOverall = isFirst && offset == 0
            val isLastChunkOverall  = isLast  && end == pcm.size
            val ok = transport.sendTtsChunk(chunk, start = isFirstChunkOverall, end = isLastChunkOverall)
            if (!ok) {
                Log.w(TAG, "chunk $chunkIdx send failed at totalSent=${state.totalSent}")
                return false
            }
            offset = end
            chunkIdx++
            state.totalSent += chunkSize
            if (!state.hasPrimed && state.totalSent >= WATCH_PREBUFFER_BYTES) {
                state.speakerStartMs = System.currentTimeMillis()
                state.hasPrimed = true
                Log.i(TAG, "tts: +${state.speakerStartMs - state.startMs}ms prebuffer (${WATCH_PREBUFFER_BYTES}B) sent — watch should open speaker now")
            }

            // Cap watch-ring usage rather than chase wall-vs-expected-audio
            // pacing. The latter falls apart across sentence boundaries
            // (await advances wall time but not totalSent, then the next
            // sentence's first chunks burst at BLE rate into a near-full
            // ring → overflow → dropped bytes → audible cuts mid-message).
            //
            // Ring-usage = bytes sent - bytes the speaker has drained since
            // it opened. Hold below MAX_RING so the next chunk has somewhere
            // safe to land. When we're behind (under MAX), send freely —
            // BLE caps the catch-up rate naturally at ~10-12 KB/s.
            if (state.hasPrimed && !(isLast && offset == pcm.size)) {
                val playedMs = (System.currentTimeMillis() - state.speakerStartMs)
                    .coerceAtLeast(0L)
                val playedBytes = playedMs * PLAYBACK_BYTES_PER_SEC / 1000L
                val ringUsage = state.totalSent - playedBytes
                if (ringUsage > MAX_RING_USAGE) {
                    val overshoot = ringUsage - MAX_RING_USAGE
                    val drainMs = overshoot * 1000L / PLAYBACK_BYTES_PER_SEC
                    delay(drainMs)
                }
            }
        }
        val wall = System.currentTimeMillis() - state.startMs
        Log.i(TAG, "stream stage done: +${pcm.size}B total=${state.totalSent}B wall=${wall}ms first=$isFirst last=$isLast")
        return true
    }

    /** Single-shot stream — sentence-pipelining isn't relevant for the
     *  diagnostic tone path, but we route through the same pacing code
     *  so back-pressure behaviour matches what real TTS sees. */
    private suspend fun streamChunks(pcm: ByteArray, chunkBytes: Int): Boolean =
        streamPcmContinuation(pcm, chunkBytes, StreamState(), isFirst = true, isLast = true)

    companion object {
        // AppMessage inbox on emery is `app_message_inbox_size_maximum()`,
        // around 8 KB. 6 KB / chunk leaves room for the start/end UInt8
        // tuples + key headers. Fewer, larger chunks means the inbox
        // callback fires less often, which reduces opportunities for
        // jitter that costs the watch a buffer top-up.
        const val DEFAULT_CHUNK_BYTES = 6144

        /** Watch ring pre-buffer threshold — keep in sync with
         *  `TTS_START_THRESHOLD` in `tts/tts.c`. Lower = faster
         *  time-to-first-audio (~500 ms at 4 KB vs ~1.4 s at 11 KB).
         *  Ring-usage pacing keeps the ring topped up regardless of
         *  where we open the speaker, so we don't need a big upfront
         *  buffer for underrun protection. */
        private const val WATCH_PREBUFFER_BYTES = 4096

        /** emery speaker fixed rate: 8 kHz × 1 byte/sample = 8000 B/s. */
        private const val PLAYBACK_BYTES_PER_SEC = 8000L

        /** Cap on watch ring usage during streaming — keeps us safely under
         *  the 12 KB ring cap (TTS_RING_BYTES) so the next chunk has room
         *  to land. ~600 B headroom = 75 ms of audio at 8 kHz, well below
         *  BLE jitter so we don't oversubscribe. */
        private const val MAX_RING_USAGE = 11500L

        // Sentence-splitter constants + regex now live in
        // com.lazydevs.wristotle.speech.nlu.tts.SentenceSplitter (commonMain)
        // so they're testable from commonTest. TtsStreamer calls the lifted
        // helper from splitIntoSentences above.
    }
}
