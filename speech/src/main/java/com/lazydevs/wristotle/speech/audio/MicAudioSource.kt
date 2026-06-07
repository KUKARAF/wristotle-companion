// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TAG = "MicAudioSource"

/**
 * 16 kHz / mono / PCM-16 capture from the device microphone, using
 * [MediaRecorder.AudioSource.VOICE_RECOGNITION] so the platform applies
 * AGC/noise-suppression tuned for speech.
 *
 * Caller must hold [Manifest.permission.RECORD_AUDIO]. The flow is cold; capture
 * starts on collect and stops when the collecting coroutine is cancelled.
 */
class MicAudioSource : AudioSource {

    override val sampleRate = SAMPLE_RATE
    override val channelCount = 1

    @Volatile private var stopRequested = false

    override fun stop() { stopRequested = true }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun samples(): Flow<ShortArray> = flow {
        // Reset the stop flag for this collection. The class is contracted as a
        // single-active-session source, but resetting here lets a fresh collection
        // start cleanly even if an earlier session left the flag set.
        stopRequested = false

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 4x the min keeps us underrun-safe when other audio activity competes.
        val bufSizeBytes = maxOf(minBuf, CHUNK_SHORTS * 2 * 4)

        @SuppressLint("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSizeBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord failed to initialize")
        }

        try {
            record.startRecording()
            val chunk = ShortArray(CHUNK_SHORTS)
            while (!stopRequested) {
                val read = record.read(chunk, 0, chunk.size)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
                if (read == 0) continue
                // Emit a defensive copy so downstream can hold the buffer past
                // the next read without seeing it mutate.
                emit(if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 100 ms at 16 kHz mono. */
        const val CHUNK_SHORTS = 1_600
    }
}