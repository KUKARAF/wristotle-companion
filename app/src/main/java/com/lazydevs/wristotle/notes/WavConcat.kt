// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Pure WAV-concat implementation extracted from [NotesAudioStore] so it can
 * be unit-tested without an Android [android.content.Context].
 *
 * Streams `source.pcmData` onto `target`, rewriting the RIFF / data size
 * fields. WAV header layout (44 bytes for the simple PCM case the
 * dictation pipeline writes):
 *   off 4..7   — RIFF chunk size (file size − 8)
 *   off 40..43 — data subchunk size
 *
 * Streams in [WAV_STREAM_BUFFER_BYTES] chunks rather than loading the full
 * source into a `ByteArray`, so memory stays bounded for long clips.
 *
 * Validates RIFF magic + sample-rate match on both files; throws on
 * malformed or mismatched input so the caller can fall back to writing a
 * fresh file instead of corrupting the target.
 *
 * Returns the number of PCM bytes appended.
 */
internal const val WAV_HEADER_SIZE = 44
/** 16 KiB transfer buffer — amortises the per-syscall overhead without
 *  meaningful GC pressure on old hardware. */
internal const val WAV_STREAM_BUFFER_BYTES = 16 * 1024

internal fun appendWav(target: File, source: File): Long {
    require(source.length() > WAV_HEADER_SIZE) { "source too small to be a WAV" }
    BufferedInputStream(FileInputStream(source)).use { src ->
        val srcHeader = ByteArray(WAV_HEADER_SIZE)
        DataInputStream(src).readFully(srcHeader)
        require(String(srcHeader, 0, 4, Charsets.US_ASCII) == "RIFF") { "source is not WAV" }
        val sourceRate = readInt32LE(srcHeader, 24)
        RandomAccessFile(target, "rw").use { raf ->
            val tgtHeader = ByteArray(WAV_HEADER_SIZE)
            raf.readFully(tgtHeader)
            require(String(tgtHeader, 0, 4, Charsets.US_ASCII) == "RIFF") { "target is not WAV" }
            val targetRate = readInt32LE(tgtHeader, 24)
            require(targetRate == sourceRate) {
                "sample-rate mismatch (target=$targetRate vs source=$sourceRate)"
            }
            val existingDataSize = readInt32LE(tgtHeader, 40)

            // 1. Stream PCM payload from src → end of target.
            raf.seek(raf.length())
            val buf = ByteArray(WAV_STREAM_BUFFER_BYTES)
            var appended = 0L
            while (true) {
                val read = src.read(buf)
                if (read <= 0) break
                raf.write(buf, 0, read)
                appended += read
            }

            // 2. Rewrite size fields. RIFF chunk size = file size − 8 =
            //    36 (everything before the data subchunk size) + dataSize.
            val newDataSize = existingDataSize + appended.toInt()
            writeInt32LEAt(raf, 4, newDataSize + 36)
            writeInt32LEAt(raf, 40, newDataSize)
            return appended
        }
    }
}

private fun readInt32LE(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

private fun writeInt32LEAt(raf: RandomAccessFile, offset: Long, value: Int) {
    raf.seek(offset)
    raf.write(byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    ))
}