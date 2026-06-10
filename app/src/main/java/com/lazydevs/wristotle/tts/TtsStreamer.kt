// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.lazydevs.wristotle.logging.WristotleLogger
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import com.lazydevs.wristotle.speech.nlu.tts.PebblePcmConverter
import com.lazydevs.wristotle.speech.nlu.tts.TtsProvider
import com.lazydevs.wristotle.speech.nlu.tts.TtsResult
import java.io.File

private const val TAG = "TtsStreamer"

/**
 * Path B: ask the configured [TtsProvider] for a WAV, run it through
 * [PebblePcmConverter] (DSP pipeline → 8 kHz signed 8-bit mono PCM), and
 * ship the PCM to the watch in chunked AppMessages via
 * [WatchTransport.sendTtsChunk]. The watch's `tts/tts.c` module buffers
 * and drains it to `speaker_stream_write`.
 */
class TtsStreamer(
    context: Context,
    private val transport: WatchTransport,
    private val provider: TtsProvider,
) {
    private val appContext = context.applicationContext

    /** Synthesize [text] → PCM → chunked send. Suspends until the last
     *  chunk has been ACKed. Returns `null` on success; a short
     *  user-facing reason string on failure (so the Settings card and
     *  any future error UI can show what went wrong without forcing
     *  the user into logcat). */
    suspend fun speak(text: String, chunkBytes: Int = DEFAULT_CHUNK_BYTES): String? {
        return when (val synth = provider.synthesizeToWav(text)) {
            is TtsResult.Failure -> {
                Log.w(TAG, "${provider.displayName} failed: ${synth.reason}")
                synth.reason
            }
            is TtsResult.Success -> {
                val wavBytes = synth.wavBytes
                val decoded = PebblePcmConverter.decodeWavToMonoS16(wavBytes, WristotleLogger)
                if (decoded == null) {
                    Log.w(TAG, "wav decode failed (bytes=${wavBytes.size})")
                    return "WAV decode failed (${wavBytes.size} B response — wrong response_format?)"
                }
                val (s16, srcRate) = decoded
                val pcm = PebblePcmConverter.convert(s16, srcRate, WristotleLogger)
                Log.i(TAG, "synthesized ${text.length} chars → ${pcm.size} bytes 8kHz/8bit PCM")
                if (streamChunks(pcm, chunkBytes)) null
                else "watch send failed (BLE disconnected?)"
            }
        }
    }

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
        runCatching { File(appContext.cacheDir, "tts_spike.pcm").writeBytes(pcm) }
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
        Thread {
            try { Thread.sleep((s8Pcm.size * 1000L / sampleRate) + 200L) } catch (_: InterruptedException) {}
            track.stop()
            track.release()
        }.start()
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
        // AppMessage inbox on emery is `app_message_inbox_size_maximum()`,
        // around 8 KB. 6 KB / chunk leaves room for the start/end UInt8
        // tuples + key headers. Fewer, larger chunks means the inbox
        // callback fires less often, which reduces opportunities for
        // jitter that costs the watch a buffer top-up.
        const val DEFAULT_CHUNK_BYTES = 6144
    }
}
