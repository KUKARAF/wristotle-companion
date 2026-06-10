// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "WavToPebblePcm"

private const val TARGET_RATE = 8000

/**
 * Read [wav], parse the RIFF header, and return signed 8-bit mono PCM at
 * 8 kHz — the format the emery speaker accepts (`SpeakerPcmFormat_8kHz_8bit`).
 *
 * Three-stage pipeline tuned for the watch speaker:
 *
 *  1. Decode WAV to s16 mono at the source rate (mix stereo by averaging).
 *  2. Anti-alias low-pass at ~3.5 kHz with a 31-tap windowed-sinc FIR, then
 *     resample to 8 kHz with linear interpolation between filtered samples.
 *     Without the low-pass, every frequency above 4 kHz folds back as
 *     aliasing — audible as crackle on the watch speaker.
 *  3. Peak-normalize the resampled signal and quantize to s8 with TPDF
 *     dither. Plain truncation to the high byte loses 8 bits of resolution
 *     and clips peaks; this preserves dynamic range and masks the
 *     quantization steps with low-amplitude noise the ear filters out.
 */
internal fun wavToPebblePcm(wav: File): ByteArray? {
    val (s16, srcRate) = decodeWavToMonoS16(wav) ?: return null
    val gated = noiseGate(s16, srcRate)
    val compressed = compressDynamicRange(gated, srcRate)
    val emphasized = preEmphasis(compressed, alpha = 0.92)
    val resampled = filterAndResample(emphasized, srcRate, TARGET_RATE)
    val out = quantizeToS8Dithered(resampled)
    Log.i(TAG, "${s16.size} samples @ ${srcRate}Hz → ${out.size} samples @ ${TARGET_RATE}Hz / s8")
    return out
}

// ── Noise gate ──────────────────────────────────────────────────────────────
//
// The companion's compressor + sqrt-companding stages boost the noise floor
// during quiet passages — silences fill with hiss otherwise. Squelch below
// `THRESHOLD` so pauses go to digital zero; smooth gain ramps avoid the
// classic noise-gate "stutter" on sibilants.
private const val GATE_THRESHOLD = 350.0     // s16 amplitude (~-40 dBFS)
private const val GATE_HOLD_MS = 40.0
private const val GATE_RAMP_MS = 8.0

private fun noiseGate(input: ShortArray, srcRate: Int): ShortArray {
    if (input.isEmpty()) return input
    val rampCoeff = exp(-1.0 / (srcRate * GATE_RAMP_MS / 1000.0))
    val envCoeff = exp(-1.0 / (srcRate * 5.0 / 1000.0))  // 5 ms env follower
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
        // Symmetric attack/release for the gain ramp.
        gain = gain * rampCoeff + target * (1.0 - rampCoeff)
        out[i] = (input[i] * gain).toInt().toShort()
    }
    return out
}

/** Pre-emphasis high-shelf: `y[n] = x[n] - α·x[n-1]`. Standard speech-codec
 *  trick that boosts ~1.5-3.5 kHz "presence" before the anti-alias filter
 *  cuts at 3500 Hz. α = 0.92 gives ~+6 dB at the upper edge of the speech
 *  band; the watch speaker's own roll-off then partly undoes it, leaving a
 *  flatter perceived spectrum. */
private fun preEmphasis(input: ShortArray, alpha: Double): ShortArray {
    if (input.isEmpty()) return input
    val out = ShortArray(input.size)
    out[0] = input[0]
    for (i in 1 until input.size) {
        val v = input[i].toInt() - alpha * input[i - 1].toInt()
        out[i] = max(-32767.0, min(32767.0, v)).toInt().toShort()
    }
    return out
}

// ── Dynamic range compressor ────────────────────────────────────────────────
//
// Speech has ~60-80 dB dynamic range. 8-bit linear PCM only has ~48 dB. A peak
// follower + soft-knee compressor squashes the dynamic range so the speech
// uses more of the available 8-bit code-points after final normalization.
// Without this the RMS speech level sits at ~5 bits of resolution and every
// syllable rides on top of quantization noise → audible crackle.

private const val COMPRESSOR_THRESHOLD = 2000.0   // ~-24 dBFS for s16
private const val COMPRESSOR_RATIO = 6.0
private const val COMPRESSOR_ATTACK_MS = 3.0
private const val COMPRESSOR_RELEASE_MS = 100.0
private const val COMPRESSOR_MAKEUP_TARGET = 28000.0  // post-compress peak, leaves headroom

private fun compressDynamicRange(input: ShortArray, srcRate: Int): ShortArray {
    val attackCoeff = exp(-1.0 / (srcRate * COMPRESSOR_ATTACK_MS / 1000.0))
    val releaseCoeff = exp(-1.0 / (srcRate * COMPRESSOR_RELEASE_MS / 1000.0))
    val gained = DoubleArray(input.size)

    // First pass: envelope follower + per-sample gain reduction.
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

    // Second pass: makeup gain to bring the new peak back up to ~28000.
    var peak = 1.0
    for (v in gained) { val a = abs(v); if (a > peak) peak = a }
    val makeup = COMPRESSOR_MAKEUP_TARGET / peak

    val out = ShortArray(input.size)
    for (i in input.indices) {
        val v = gained[i] * makeup
        out[i] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
    }
    Log.i(TAG, "compressor: src peak ${input.maxOf { abs(it.toInt()) }} → out peak ${out.maxOf { abs(it.toInt()) }} (makeup=${"%.2f".format(makeup)})")
    return out
}

// ── 1. WAV → s16 mono ────────────────────────────────────────────────────────

private fun decodeWavToMonoS16(wav: File): Pair<ShortArray, Int>? {
    val bytes = wav.readBytes()
    if (bytes.size < 44) {
        Log.w(TAG, "wav too short: ${bytes.size}")
        return null
    }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
        Log.w(TAG, "not a RIFF/WAVE file")
        return null
    }
    // Walk subchunks — Android TTS may insert a LIST between fmt and data.
    var pos = 12
    var sampleRate = 0
    var bitsPerSample = 0
    var numChannels = 0
    var dataOffset = -1
    var dataLength = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val sz = buf.getInt(pos + 4)
        when (id) {
            "fmt " -> {
                numChannels   = buf.getShort(pos + 10).toInt()
                sampleRate    = buf.getInt(pos + 12)
                bitsPerSample = buf.getShort(pos + 22).toInt()
            }
            "data" -> { dataOffset = pos + 8; dataLength = sz }
        }
        pos += 8 + sz
        if (sz % 2 == 1) pos++
        if (dataOffset >= 0 && sampleRate > 0) break
    }
    if (dataOffset < 0 || sampleRate == 0 || bitsPerSample == 0 || numChannels == 0) {
        Log.w(TAG, "couldn't find fmt/data chunks")
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
            if (co + bytesPerSample > bytes.size) break
            val s = when (bitsPerSample) {
                8  -> ((bytes[co].toInt() and 0xff) - 128) shl 8 // unsigned → signed s16
                16 -> ((bytes[co + 1].toInt() shl 8) or (bytes[co].toInt() and 0xff)).toShort().toInt()
                else -> 0
            }
            acc += s
        }
        s16[i] = (acc / numChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    Log.i(TAG, "wav: rate=$sampleRate bits=$bitsPerSample ch=$numChannels frames=$numFrames")
    return s16 to sampleRate
}

// ── 2. Low-pass + linear-interp resample ─────────────────────────────────────

private const val LP_NUM_TAPS = 31           // odd, symmetric around center
private const val LP_CUTOFF_HZ = 3500.0      // below 4 kHz Nyquist for 8 kHz output

private fun filterAndResample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
    if (srcRate == dstRate) return input
    val coeffs = hannSincLowpass(LP_NUM_TAPS, LP_CUTOFF_HZ, srcRate.toDouble())
    val filtered = applyFir(input, coeffs)
    return linearResample(filtered, srcRate, dstRate)
}

/** Windowed-sinc low-pass coefficients. Normalised so they sum to 1.0
 *  → zero DC gain shift. */
private fun hannSincLowpass(numTaps: Int, cutoffHz: Double, fs: Double): DoubleArray {
    val coeffs = DoubleArray(numTaps)
    val center = (numTaps - 1) / 2.0
    val cutoffNorm = cutoffHz / fs
    var sum = 0.0
    for (n in 0 until numTaps) {
        val x = n - center
        val sinc = if (x == 0.0) 2.0 * cutoffNorm
                   else sin(2.0 * PI * cutoffNorm * x) / (PI * x)
        val win = 0.5 * (1.0 - cos(2.0 * PI * n / (numTaps - 1))) // Hann
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

// ── 3. Peak-normalise + TPDF-dithered 8-bit quantization ─────────────────────

/** Map to ±124 (not 127) so dither + headroom can't clip. */
private const val S8_PEAK = 124.0

private fun quantizeToS8Dithered(input: ShortArray): ByteArray {
    var peak = 1
    for (s in input) {
        val a = abs(s.toInt())
        if (a > peak) peak = a
    }
    // sqrt-based "wire compander" — companding the OUTGOING bytes before
    // they hit the (linear-PCM) speaker. The speaker decodes them as if
    // they were linear, so the audio is distorted (quiet samples are
    // boosted relative to source), but the 8-bit code-points are far more
    // densely allocated near zero where speech detail lives. Approximates
    // a 6 dB+ effective SNR improvement at speech RMS levels.
    val sqrtPeak = sqrt(peak.toDouble())
    val scale = S8_PEAK / sqrtPeak

    var rng = 0x1234L
    val out = ByteArray(input.size)
    for (i in input.indices) {
        val u1 = nextDouble(rng).also { rng = (rng * 6364136223846793005L + 1442695040888963407L) }
        val u2 = nextDouble(rng).also { rng = (rng * 6364136223846793005L + 1442695040888963407L) }
        val dither = u1 - u2
        val absS = abs(input[i].toDouble())
        val sgn = if (input[i] < 0) -1.0 else 1.0
        val v = sgn * sqrt(absS) * scale + dither
        val clamped = max(-127.0, min(127.0, v))
        out[i] = clamped.toInt().toByte()
    }
    return out
}

private fun nextDouble(state: Long): Double {
    val v = (state ushr 11) and ((1L shl 53) - 1)
    return v.toDouble() / (1L shl 53).toDouble()
}
