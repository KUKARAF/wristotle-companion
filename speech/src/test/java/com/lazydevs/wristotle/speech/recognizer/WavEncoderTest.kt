// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Header-shape + round-trip checks for [WavEncoder]. Confirms the
 * binary layout matches the canonical RIFF/WAV spec so providers (which
 * blindly trust the header for sample rate / format) don't misinterpret
 * the audio.
 */
class WavEncoderTest {

    @Test
    fun `header has RIFF, WAVE, fmt, data tags at canonical offsets`() {
        val out = WavEncoder.encode(ShortArray(1024) { 0 }, sampleRate = 16_000)
        assertEquals("RIFF", String(out.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(out.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(out.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(out.copyOfRange(36, 40), Charsets.US_ASCII))
    }

    @Test
    fun `fmt chunk encodes PCM mono 16-bit at the requested sample rate`() {
        val out = WavEncoder.encode(ShortArray(100) { 0 }, sampleRate = 16_000)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16, buf.getInt(16))               // Subchunk1Size
        assertEquals(1.toShort(), buf.getShort(20))    // AudioFormat = PCM
        assertEquals(1.toShort(), buf.getShort(22))    // NumChannels = mono
        assertEquals(16_000, buf.getInt(24))           // SampleRate
        assertEquals(32_000, buf.getInt(28))           // ByteRate = 16k * 1 * 16/8
        assertEquals(2.toShort(), buf.getShort(32))    // BlockAlign
        assertEquals(16.toShort(), buf.getShort(34))   // BitsPerSample
    }

    @Test
    fun `data subchunk size matches samples times two bytes`() {
        val samples = ShortArray(3_200) { it.toShort() } // 200 ms of 16k
        val out = WavEncoder.encode(samples, sampleRate = 16_000)
        val dataSize = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        assertEquals(samples.size * 2, dataSize)
        assertEquals(44 + samples.size * 2, out.size)
    }

    @Test
    fun `riff chunk size is 36 plus data size`() {
        val samples = ShortArray(100) { it.toShort() }
        val out = WavEncoder.encode(samples, sampleRate = 16_000)
        val riff = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        assertEquals(36 + samples.size * 2, riff)
    }

    @Test
    fun `samples round-trip as little-endian shorts after the header`() {
        val samples = shortArrayOf(0, 1, -1, 32_767, -32_768, 256, -256)
        val out = WavEncoder.encode(samples, sampleRate = 16_000)
        val body = out.copyOfRange(44, out.size)
        val decoded = ShortArray(samples.size).also {
            val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            for (i in it.indices) it[i] = buf.short
        }
        assertArrayEquals(samples, decoded)
    }

    @Test
    fun `empty samples produces a valid 44-byte header with zero data`() {
        val out = WavEncoder.encode(ShortArray(0), sampleRate = 16_000)
        assertEquals(44, out.size)
        val dataSize = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
        assertEquals(0, dataSize)
    }
}