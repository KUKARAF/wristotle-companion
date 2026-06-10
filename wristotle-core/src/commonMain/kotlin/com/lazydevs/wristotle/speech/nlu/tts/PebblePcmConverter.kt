// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import com.lazydevs.wristotle.speech.nlu.logging.Logger
import com.lazydevs.wristotle.speech.nlu.logging.NoopLogger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "PebblePcmConverter"

/** Output sample rate for emery's `SpeakerPcmFormat_8kHz_8bit` speaker. */
const val PEBBLE_TARGET_RATE: Int = 8000

/**
 * Pure-Kotlin DSP pipeline that turns 16-bit signed mono PCM (at any source
 * rate) into the 8 kHz signed 8-bit linear PCM the emery watch speaker
 * accepts. Speech-tuned: noise gate → dynamic range compressor →
 * pre-emphasis → windowed-sinc low-pass → linear-interp resample → sqrt-
 * companding quantize with TPDF dither.
 *
 * Speech ends up sounding like a phone-call (telephony band, mild
 * companding distortion); pure tones sound thin because the format is
 * 8-bit linear and the speaker is a tiny Class-D. See on-watch-tts.md for
 * the spike rationale + listening-test history.
 */
object PebblePcmConverter {

    fun convert(s16Mono: ShortArray, srcRate: Int, log: Logger = NoopLogger): ByteArray {
        val gated = noiseGate(s16Mono, srcRate)
        val compressed = compressDynamicRange(gated, srcRate, log)
        val emphasized = preEmphasis(compressed, alpha = 0.92)
        val resampled = filterAndResample(emphasized, srcRate, PEBBLE_TARGET_RATE)
        return quantizeToS8Dithered(resampled)
    }

    /**
     * Decode a RIFF/WAVE byte stream to 16-bit signed mono PCM at its
     * source rate. Stereo gets mixed down by averaging channels. 8-bit
     * unsigned source is shifted to 16-bit signed. Returns null on a
     * malformed header (no fmt / no data / unknown bit depth).
     */
    fun decodeWavToMonoS16(wavBytes: ByteArray, log: Logger = NoopLogger): Pair<ShortArray, Int>? {
        if (wavBytes.size < 44) {
            log.w(TAG, "wav too short: ${wavBytes.size}")
            return null
        }
        if (!wavBytes.startsWith("RIFF") || !wavBytes.regionEquals(8, "WAVE")) {
            log.w(TAG, "not a RIFF/WAVE file")
            return null
        }
        // Walk subchunks — Android TTS may insert a LIST between fmt and data.
        var pos = 12
        var sampleRate = 0
        var bitsPerSample = 0
        var numChannels = 0
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= wavBytes.size) {
            val id = wavBytes.asciiAt(pos, 4)
            val sz = wavBytes.leI32(pos + 4)
            when (id) {
                "fmt " -> {
                    numChannels   = wavBytes.leI16(pos + 10)
                    sampleRate    = wavBytes.leI32(pos + 12)
                    bitsPerSample = wavBytes.leI16(pos + 22)
                }
                "data" -> { dataOffset = pos + 8; dataLength = sz }
            }
            pos += 8 + sz
            if (sz % 2 == 1) pos++
            if (dataOffset >= 0 && sampleRate > 0) break
        }
        if (dataOffset < 0 || sampleRate == 0 || bitsPerSample == 0 || numChannels == 0) {
            log.w(TAG, "couldn't find fmt/data chunks")
            return null
        }
        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * numChannels
        val numFrames = dataLength / frameSize
        val s16 = ShortArray(numFrames)
        for (i in 0 until numFrames) {
            val frameStart = dataOffset + i * frameSize
            var acc = 0
            for (c in 0 until numChannels) {
                val co = frameStart + c * bytesPerSample
                if (co + bytesPerSample > wavBytes.size) break
                val s = when (bitsPerSample) {
                    8  -> ((wavBytes[co].toInt() and 0xff) - 128) shl 8
                    16 -> wavBytes.leI16(co).toShort().toInt()
                    else -> 0
                }
                acc += s
            }
            s16[i] = (acc / numChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        log.d(TAG, "wav: rate=$sampleRate bits=$bitsPerSample ch=$numChannels frames=$numFrames")
        return s16 to sampleRate
    }
}

// ── Noise gate ──────────────────────────────────────────────────────────────

private const val GATE_THRESHOLD = 350.0
private const val GATE_HOLD_MS = 40.0
private const val GATE_RAMP_MS = 8.0

internal fun noiseGate(input: ShortArray, srcRate: Int): ShortArray {
    if (input.isEmpty()) return input
    val rampCoeff = exp(-1.0 / (srcRate * GATE_RAMP_MS / 1000.0))
    val envCoeff = exp(-1.0 / (srcRate * 5.0 / 1000.0))
    val holdSamples = (srcRate * GATE_HOLD_MS / 1000.0).toInt()

    var envelope = 0.0
    var quietRun = 0
    var gain = 1.0
    val out = ShortArray(input.size)
    for (i in input.indices) {
        val absS = abs(input[i].toInt()).toDouble()
        envelope = envelope * envCoeff + absS * (1.0 - envCoeff)
        quietRun = if (envelope < GATE_THRESHOLD) quietRun + 1 else 0
        val target = if (quietRun > holdSamples) 0.0 else 1.0
        gain = gain * rampCoeff + target * (1.0 - rampCoeff)
        out[i] = (input[i] * gain).toInt().toShort()
    }
    return out
}

// ── Dynamic range compressor ────────────────────────────────────────────────

private const val COMPRESSOR_THRESHOLD = 2000.0
private const val COMPRESSOR_RATIO = 6.0
private const val COMPRESSOR_ATTACK_MS = 3.0
private const val COMPRESSOR_RELEASE_MS = 100.0
private const val COMPRESSOR_MAKEUP_TARGET = 28000.0

internal fun compressDynamicRange(input: ShortArray, srcRate: Int, log: Logger): ShortArray {
    if (input.isEmpty()) return input
    val attackCoeff = exp(-1.0 / (srcRate * COMPRESSOR_ATTACK_MS / 1000.0))
    val releaseCoeff = exp(-1.0 / (srcRate * COMPRESSOR_RELEASE_MS / 1000.0))
    val gained = DoubleArray(input.size)

    var envelope = 0.0
    for (i in input.indices) {
        val absS = abs(input[i].toInt()).toDouble()
        envelope = if (absS > envelope) {
            envelope * attackCoeff + absS * (1.0 - attackCoeff)
        } else {
            envelope * releaseCoeff + absS * (1.0 - releaseCoeff)
        }
        val gain = if (envelope > COMPRESSOR_THRESHOLD) {
            val overshoot = envelope - COMPRESSOR_THRESHOLD
            (COMPRESSOR_THRESHOLD + overshoot / COMPRESSOR_RATIO) / envelope
        } else 1.0
        gained[i] = input[i] * gain
    }

    var peak = 1.0
    for (v in gained) { val a = abs(v); if (a > peak) peak = a }
    val makeup = COMPRESSOR_MAKEUP_TARGET / peak
    val out = ShortArray(input.size)
    for (i in input.indices) {
        val v = gained[i] * makeup
        out[i] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
    }
    log.d(TAG, "compressor: makeup=$makeup")
    return out
}

// ── Pre-emphasis ────────────────────────────────────────────────────────────

internal fun preEmphasis(input: ShortArray, alpha: Double): ShortArray {
    if (input.isEmpty()) return input
    val out = ShortArray(input.size)
    out[0] = input[0]
    for (i in 1 until input.size) {
        val v = input[i].toInt() - alpha * input[i - 1].toInt()
        out[i] = max(-32767.0, min(32767.0, v)).toInt().toShort()
    }
    return out
}

// ── Anti-alias FIR + linear-interp resample ─────────────────────────────────

private const val LP_NUM_TAPS = 31
private const val LP_CUTOFF_HZ = 3500.0

internal fun filterAndResample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
    if (srcRate == dstRate || input.isEmpty()) return input
    val coeffs = hannSincLowpass(LP_NUM_TAPS, LP_CUTOFF_HZ, srcRate.toDouble())
    val filtered = applyFir(input, coeffs)
    return linearResample(filtered, srcRate, dstRate)
}

private fun hannSincLowpass(numTaps: Int, cutoffHz: Double, fs: Double): DoubleArray {
    val coeffs = DoubleArray(numTaps)
    val center = (numTaps - 1) / 2.0
    val cutoffNorm = cutoffHz / fs
    var sum = 0.0
    for (n in 0 until numTaps) {
        val x = n - center
        val sinc = if (x == 0.0) 2.0 * cutoffNorm
                   else sin(2.0 * PI * cutoffNorm * x) / (PI * x)
        val win = 0.5 * (1.0 - cos(2.0 * PI * n / (numTaps - 1)))
        coeffs[n] = sinc * win
        sum += coeffs[n]
    }
    for (n in 0 until numTaps) coeffs[n] /= sum
    return coeffs
}

private fun applyFir(input: ShortArray, coeffs: DoubleArray): DoubleArray {
    val out = DoubleArray(input.size)
    val center = coeffs.size / 2
    for (i in input.indices) {
        var acc = 0.0
        for (k in coeffs.indices) {
            val j = i + k - center
            if (j in input.indices) acc += input[j] * coeffs[k]
        }
        out[i] = acc
    }
    return out
}

private fun linearResample(input: DoubleArray, srcRate: Int, dstRate: Int): ShortArray {
    val outLen = (input.size.toLong() * dstRate / srcRate).toInt()
    val out = ShortArray(outLen)
    val ratio = srcRate.toDouble() / dstRate.toDouble()
    for (i in 0 until outLen) {
        val srcPos = i * ratio
        val idx0 = srcPos.toInt()
        val frac = srcPos - idx0
        val a = if (idx0 in input.indices) input[idx0] else 0.0
        val b = if (idx0 + 1 in input.indices) input[idx0 + 1] else a
        val v = a + (b - a) * frac
        out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return out
}

// ── Sqrt-companding s8 quantization with TPDF dither ────────────────────────

private const val S8_PEAK = 124.0

internal fun quantizeToS8Dithered(input: ShortArray): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    var peak = 1
    for (s in input) {
        val a = abs(s.toInt())
        if (a > peak) peak = a
    }
    val sqrtPeak = sqrt(peak.toDouble())
    val scale = S8_PEAK / sqrtPeak

    var rng = 0x1234L
    val out = ByteArray(input.size)
    for (i in input.indices) {
        val u1 = lcgUniform(rng).also { rng = lcgStep(rng) }
        val u2 = lcgUniform(rng).also { rng = lcgStep(rng) }
        val dither = u1 - u2
        val absS = abs(input[i].toDouble())
        val sgn = if (input[i] < 0) -1.0 else 1.0
        val v = sgn * sqrt(absS) * scale + dither
        out[i] = max(-127.0, min(127.0, v)).toInt().toByte()
    }
    return out
}

private fun lcgStep(state: Long): Long = state * 6364136223846793005L + 1442695040888963407L

private fun lcgUniform(state: Long): Double {
    val v = (state ushr 11) and ((1L shl 53) - 1)
    return v.toDouble() / (1L shl 53).toDouble()
}

// ── Byte-array helpers (replace java.nio.ByteBuffer) ────────────────────────

private fun ByteArray.leI16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.leI32(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl 8) or
    ((this[offset + 2].toInt() and 0xff) shl 16) or
    ((this[offset + 3].toInt() and 0xff) shl 24)

private fun ByteArray.asciiAt(offset: Int, length: Int): String {
    val chars = CharArray(length)
    for (i in 0 until length) chars[i] = (this[offset + i].toInt() and 0xff).toChar()
    return chars.concatToString()
}

private fun ByteArray.startsWith(prefix: String): Boolean = regionEquals(0, prefix)

private fun ByteArray.regionEquals(offset: Int, expected: String): Boolean {
    if (offset + expected.length > size) return false
    for (i in expected.indices) if (this[offset + i].toInt().toChar() != expected[i]) return false
    return true
}

