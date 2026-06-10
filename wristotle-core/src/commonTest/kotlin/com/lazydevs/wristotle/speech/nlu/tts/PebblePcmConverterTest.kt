// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PebblePcmConverterTest {

    @Test fun `convert produces 8 kHz s8 output for a 22050 Hz source`() {
        val srcRate = 22050
        val numSamples = srcRate * 3   // 3 seconds
        val src = ShortArray(numSamples) { i ->
            (sin(2.0 * PI * 440.0 * i / srcRate) * 16000).toInt().toShort()
        }
        val out = PebblePcmConverter.convert(src, srcRate)
        // 3 sec @ 8 kHz = 24000 samples
        assertEquals(24000, out.size)
    }

    @Test fun `convert preserves peak in s8 range and below clipping ceiling`() {
        val srcRate = 16000
        val src = ShortArray(srcRate / 2) { i ->
            (sin(2.0 * PI * 1000.0 * i / srcRate) * 30000).toInt().toShort()
        }
        val out = PebblePcmConverter.convert(src, srcRate)
        var peak = 0
        for (b in out) {
            val a = abs(b.toInt())
            if (a > peak) peak = a
        }
        // Sqrt-companding + S8_PEAK=124 + dither headroom should land peak ≤ 127.
        assertTrue(peak in 100..127, "peak=$peak out of expected 100..127")
    }

    @Test fun `convert empty input returns empty bytes`() {
        val out = PebblePcmConverter.convert(ShortArray(0), 22050)
        assertEquals(0, out.size)
    }

    @Test fun `decodeWavToMonoS16 parses a synthetic mono 16-bit WAV`() {
        // Hand-roll a tiny WAV (10 samples, 16-bit signed mono @ 8 kHz).
        val samples = shortArrayOf(0, 100, 200, 300, 400, 500, 600, 700, 800, 900)
        val wav = buildPcmWav(samples, sampleRate = 8000)
        val (decoded, rate) = PebblePcmConverter.decodeWavToMonoS16(wav)!!
        assertEquals(8000, rate)
        assertEquals(samples.size, decoded.size)
        for (i in samples.indices) assertEquals(samples[i], decoded[i], "sample $i mismatch")
    }

    @Test fun `decodeWavToMonoS16 rejects garbage`() {
        assertNull(PebblePcmConverter.decodeWavToMonoS16(ByteArray(10)))
        assertNull(PebblePcmConverter.decodeWavToMonoS16("NOTRIFFGARBAGE".encodeToByteArray() + ByteArray(60)))
    }

    @Test fun `decodeWavToMonoS16 mixes stereo to mono`() {
        // Stereo: L=100, R=300 → average 200. Two frames.
        val wav = buildPcmWavRaw(
            sampleRate = 8000,
            numChannels = 2,
            bitsPerSample = 16,
            samples = byteArrayOf(
                100.toByte(), 0, 44.toByte(), 1,  // frame 0: L=100, R=300
                100.toByte(), 0, 44.toByte(), 1,  // frame 1: same
            ),
        )
        val (decoded, _) = PebblePcmConverter.decodeWavToMonoS16(wav)!!
        assertEquals(2, decoded.size)
        assertEquals(200, decoded[0].toInt())
        assertEquals(200, decoded[1].toInt())
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun buildPcmWav(samples: ShortArray, sampleRate: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return buildPcmWavRaw(sampleRate, numChannels = 1, bitsPerSample = 16, samples = bytes)
    }

    private fun buildPcmWavRaw(sampleRate: Int, numChannels: Int, bitsPerSample: Int, samples: ByteArray): ByteArray {
        val dataLen = samples.size
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val header = ByteArray(44)
        // RIFF header
        "RIFF".encodeToByteArray().copyInto(header, 0)
        writeLEI32(header, 4, 36 + dataLen)
        "WAVE".encodeToByteArray().copyInto(header, 8)
        // fmt subchunk
        "fmt ".encodeToByteArray().copyInto(header, 12)
        writeLEI32(header, 16, 16)         // fmt size
        writeLEI16(header, 20, 1)          // PCM
        writeLEI16(header, 22, numChannels)
        writeLEI32(header, 24, sampleRate)
        writeLEI32(header, 28, byteRate)
        writeLEI16(header, 32, blockAlign)
        writeLEI16(header, 34, bitsPerSample)
        // data subchunk
        "data".encodeToByteArray().copyInto(header, 36)
        writeLEI32(header, 40, dataLen)
        return header + samples
    }

    private fun writeLEI32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun writeLEI16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
    }
}
