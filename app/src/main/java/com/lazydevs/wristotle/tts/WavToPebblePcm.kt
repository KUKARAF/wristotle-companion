// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tts

import android.util.Log
import com.lazydevs.wristotle.speech.nlu.tts.PEBBLE_TARGET_RATE
import com.lazydevs.wristotle.speech.nlu.tts.PebblePcmConverter
import com.lazydevs.wristotle.logging.WristotleLogger
import java.io.File

private const val TAG = "WavToPebblePcm"

/**
 * Android thin wrapper around [PebblePcmConverter]. Reads the WAV bytes,
 * delegates the entire DSP pipeline (WAV decode + noise gate + compressor +
 * pre-emphasis + LP + resample + companding quantize) to commonMain so iOS
 * can share the same code when that port lands.
 */
internal fun wavToPebblePcm(wav: File): ByteArray? {
    val wavBytes = wav.readBytes()
    val (s16, srcRate) = PebblePcmConverter.decodeWavToMonoS16(wavBytes, WristotleLogger)
        ?: return null
    val out = PebblePcmConverter.convert(s16, srcRate, WristotleLogger)
    Log.i(TAG, "${s16.size} samples @ ${srcRate}Hz → ${out.size} samples @ ${PEBBLE_TARGET_RATE}Hz / s8")
    return out
}
