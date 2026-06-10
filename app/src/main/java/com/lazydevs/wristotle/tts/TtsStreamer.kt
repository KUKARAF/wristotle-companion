// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "TtsStreamer"

/**
 * Spike (2026-06-09) — Path B sender. Drives Android's [TextToSpeech] to
 * synthesize text to a WAV file, downsamples/requantizes to the emery
 * speaker's 8 kHz signed 8-bit mono format, then ships the PCM to the watch
 * in chunked AppMessages via [WatchTransport.sendTtsChunk].
 *
 * Spike scope: hardcoded chunk size, no error UI, single-shot. The
 * goal is to measure the watch-side throughput from the log line that
 * `tts.c::prv_end` emits at stream close.
 */
class TtsStreamer(
    context: Context,
    private val transport: WatchTransport,
) {
    private val appContext = context.applicationContext

    /**
     * Synthesize [text] → PCM → chunked send. Suspends until the last chunk
     * has been ACKed. Returns true on success, false on TTS or send failure.
     */
    suspend fun speak(text: String, chunkBytes: Int = DEFAULT_CHUNK_BYTES): Boolean {
        val wavFile = File(appContext.cacheDir, "tts_spike.wav")
        if (wavFile.exists()) wavFile.delete()

        val tts = waitForInit()
        try {
            if (!synthesizeBlocking(tts, text, wavFile)) {
                Log.w(TAG, "synthesize failed")
                return false
            }
        } finally {
            tts.shutdown()
        }

        val pcm = wavToPebblePcm(wavFile) ?: run {
            Log.w(TAG, "wav→pcm conversion failed (file=${wavFile.length()} bytes)")
            return false
        }
        Log.i(TAG, "synthesized ${text.length} chars → ${pcm.size} bytes 8kHz/8bit PCM")

        return streamChunks(pcm, chunkBytes)
    }

    /**
     * Diagnostic — bypass TTS entirely and stream a synthesized 440 Hz sine
     * wave through the same chunking pipeline. If THIS plays cleanly,
     * crackle/hiss in [speak] is the TTS source's fault. If this still
     * crackles, the buffer/BLE-pacing path is the culprit.
     */
    suspend fun playTestTone(seconds: Double = 3.0, chunkBytes: Int = DEFAULT_CHUNK_BYTES): Boolean {
        val pcm = makeSineWave(seconds, 440.0)
        Log.i(TAG, "test tone: 440Hz, ${seconds}s, ${pcm.size} bytes")
        return streamChunks(pcm, chunkBytes)
    }

    /**
     * Play the *exact* PCM we would ship to the watch through the phone's
     * speaker via [AudioTrack] at 8 kHz. Lets us A/B watch playback against
     * what the source PCM actually sounds like at this sample rate / bit
     * depth — if the phone playback is clean and the watch's crackly, the
     * watch's playback path is at fault. If the phone is also crackly, the
     * conversion pipeline is.
     */
    suspend fun speakOnPhone(text: String): Boolean {
        val wavFile = File(appContext.cacheDir, "tts_spike.wav")
        if (wavFile.exists()) wavFile.delete()
        val tts = waitForInit()
        try {
            if (!synthesizeBlocking(tts, text, wavFile)) return false
        } finally {
            tts.shutdown()
        }
        val pcm = wavToPebblePcm(wavFile) ?: return false
        // Also dump the s8 PCM to cache so we can pull + analyse outside.
        runCatching {
            File(appContext.cacheDir, "tts_spike.pcm").writeBytes(pcm)
        }
        playPcmOnPhone(pcm)
        return true
    }

    fun playTonePhone(seconds: Double = 3.0) {
        playPcmOnPhone(makeSineWave(seconds, 440.0))
    }

    private fun makeSineWave(seconds: Double, freq: Double): ByteArray {
        val numSamples = (seconds * 8000).toInt()
        val pcm = ByteArray(numSamples)
        for (i in 0 until numSamples) {
            val s = kotlin.math.sin(2.0 * kotlin.math.PI * freq * i / 8000.0)
            pcm[i] = (s * 100.0).toInt().toByte()
        }
        return pcm
    }

    private fun playPcmOnPhone(s8Pcm: ByteArray) {
        // Convert signed 8-bit → signed 16-bit by left-shift. AudioTrack
        // supports PCM_8BIT but expects UNSIGNED 8-bit (silence at 128);
        // PCM_16BIT is universally supported and lossless from our s8 input.
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
        // Schedule release after playback would have finished.
        Thread {
            try { Thread.sleep((s8Pcm.size * 1000L / sampleRate) + 200L) } catch (_: InterruptedException) {}
            track.stop()
            track.release()
        }.start()
    }

    // ── Android TextToSpeech glue ──────────────────────────────────────

    private suspend fun waitForInit(): TextToSpeech = suspendCancellableCoroutine { cont ->
        // Prefer Google TTS when present — it's usually higher-quality source
        // than vendor / Open-Source-only engines. Fall back to whatever is
        // default if Google's not installed.
        val preferred = "com.google.android.tts"
        var triedFallback = false
        lateinit var tts: TextToSpeech
        fun build(engine: String?) {
            tts = TextToSpeech(appContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val available = tts.engines.joinToString { it.name }
                    Log.i(TAG, "TTS engine in use: ${tts.defaultEngine} (available: $available)")
                    cont.resume(tts)
                } else if (!triedFallback) {
                    triedFallback = true
                    Log.w(TAG, "preferred TTS engine '$engine' failed → falling back to default")
                    tts.shutdown()
                    build(null)
                } else {
                    cont.resumeWithException(IllegalStateException("TTS init failed: $status"))
                }
            }, engine)
        }
        build(preferred)
    }

    private suspend fun synthesizeBlocking(
        tts: TextToSpeech,
        text: String,
        out: File,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val utteranceId = "tts-spike-${System.currentTimeMillis()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = Unit
            override fun onDone(id: String) { cont.resume(true) }
            @Deprecated("legacy callback")
            override fun onError(id: String) { cont.resume(false) }
            override fun onError(id: String, errorCode: Int) { cont.resume(false) }
        })
        val r = tts.synthesizeToFile(text, Bundle(), out, utteranceId)
        if (r != TextToSpeech.SUCCESS) cont.resume(false)
    }

    // ── Streaming ──────────────────────────────────────────────────────

    private suspend fun streamChunks(pcm: ByteArray, chunkBytes: Int): Boolean {
        val t0 = System.currentTimeMillis()
        var offset = 0
        var chunkIdx = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunkBytes, pcm.size)
            val chunk = pcm.copyOfRange(offset, end)
            val isFirst = offset == 0
            val isLast  = end == pcm.size
            val ok = transport.sendTtsChunk(chunk, start = isFirst, end = isLast)
            if (!ok) {
                Log.w(TAG, "chunk $chunkIdx send failed at offset $offset")
                return false
            }
            offset = end
            chunkIdx++
        }
        val elapsed = System.currentTimeMillis() - t0
        val bps = if (elapsed > 0) pcm.size * 1000L / elapsed else 0L
        Log.i(TAG, "sent ${pcm.size}B in $chunkIdx chunks in ${elapsed}ms (${bps} B/s)")
        return true
    }

    companion object {
        // AppMessage inbox on emery is `app_message_inbox_size_maximum()` —
        // around 8 KB. 1 KB / chunk = ~128 ms of audio at 8 kHz, small enough
        // that each ~50-300 ms AppMessage round-trip refills the speaker
        // buffer well before it drains. 4 KB chunks left 500 ms of audio in
        // flight per round-trip → audible gaps when delivery lagged.
        const val DEFAULT_CHUNK_BYTES = 1024
    }
}
