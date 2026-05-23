package com.lazydevs.wristotle.notes

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "NotesAudioStore"
// Pure digits, second precision: `yyyyMMddHHmmss.wav`. Sorts naturally and
// reads as a compact timestamp. Two notes in the same second would collide,
// which is fine in practice — a voice note takes longer than a second to
// dictate, so it cannot happen.
private val FILENAME_FORMAT = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

/**
 * On-disk store for per-note `.wav` files. Distinct directory from
 * `conversation-audio/` so the conversation FIFO doesn't evict a
 * note's audio out from under us.
 *
 * Files live at `filesDir/notes-audio/<noteId>.wav`. Lifecycle is
 * per-note: a file appears when a watch dictation note is saved and
 * disappears when the user deletes the note (or it's FIFO-pruned by
 * the keep-last-N cap).
 */
class NotesAudioStore(context: Context) {

    val dir: File = File(context.filesDir, DIR_NAME)

    /**
     * Copies [source] (the transient conversation-audio `.wav` for this
     * dictation) to `notes-audio/<yyyy-MM-dd_HH-mm-ss-SSS>.wav` and returns
     * the new path. Filename is built from [createdAtEpochMs] so files are
     * sortable + meaningful in a file browser. Returns null on any failure
     * — the caller treats audio as best-effort.
     */
    fun copyFromConversationAudio(source: File, createdAtEpochMs: Long): String? {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "couldn't create $dir")
                return null
            }
            val name = synchronized(FILENAME_FORMAT) { FILENAME_FORMAT.format(Date(createdAtEpochMs)) }
            val dest = File(dir, "$name.wav")
            source.copyTo(dest, overwrite = true)
            Log.d(TAG, "copied ${source.absolutePath} -> ${dest.absolutePath}")
            dest.absolutePath
        }.onFailure { Log.w(TAG, "copy failed", it) }.getOrNull()
    }

    /**
     * Append [source]'s PCM data to an existing per-note `.wav` at
     * [existingPath], rewriting the WAV header sizes so the file stays
     * playable as one continuous clip. Returns the path that should be
     * stored on the note row:
     *   - [existingPath] when the append succeeded
     *   - a new permanent path when there was no existing file or the
     *     append failed (falls back to [copyFromConversationAudio])
     * Returns null only if even the fallback copy fails.
     *
     * Assumes both files are 16-bit mono PCM at the same sample rate
     * (Wristotle's dictation pipeline always emits 16 kHz). If the
     * sample rates differ or the existing file isn't a valid WAV, the
     * fallback path is taken so no recording is silently lost.
     */
    fun appendFromConversationAudio(
        source: File,
        existingPath: String?,
        fallbackEpochMs: Long,
    ): String? {
        val existing = existingPath?.let(::File)?.takeIf { it.exists() }
        if (existing == null) {
            return copyFromConversationAudio(source, fallbackEpochMs)
        }
        val appended = runCatching { appendWav(existing, source) }
            .onFailure { Log.w(TAG, "wav append failed, falling back to new file", it) }
            .getOrDefault(false)
        return if (appended) existing.absolutePath
        else copyFromConversationAudio(source, fallbackEpochMs)
    }

    /** Deletes the note's audio file if present. Safe to call with a stale path. */
    fun delete(path: String?) {
        if (path == null) return
        runCatching {
            val f = File(path)
            if (f.parentFile?.absolutePath == dir.absolutePath && f.exists()) f.delete()
        }.onFailure { Log.w(TAG, "delete($path) failed", it) }
    }

    /** Wipes every note audio file (used when the user clears all notes). */
    fun deleteAll() {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "deleteAll failed", it) }
    }

    /**
     * Append source.pcmData onto target, rewriting the RIFF/data size
     * fields. WAV header layout (44 bytes for the simple PCM case the
     * dictation pipeline writes):
     *   off 4..7   — RIFF chunk size (file size − 8)
     *   off 40..43 — data subchunk size
     * Returns true on success; throws on malformed input so the caller
     * can fall back to a fresh file.
     */
    private fun appendWav(target: File, source: File): Boolean {
        val sourceBytes = source.readBytes()
        require(sourceBytes.size > HEADER_SIZE) { "source too small to be a WAV" }
        require(String(sourceBytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "source is not WAV" }
        val sourceRate = readInt32LE(sourceBytes, 24)
        RandomAccessFile(target, "rw").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "target is not WAV" }
            val targetRate = readInt32LE(header, 24)
            require(targetRate == sourceRate) {
                "sample-rate mismatch (target=$targetRate vs source=$sourceRate)"
            }
            val existingDataSize = readInt32LE(header, 40)
            val appendedSize = sourceBytes.size - HEADER_SIZE
            // 1. Append PCM bytes to end of file (skip the source's own header).
            raf.seek(raf.length())
            raf.write(sourceBytes, HEADER_SIZE, appendedSize)
            // 2. Rewrite the size fields. RIFF chunk size = file size − 8 =
            //    36 (everything before the data subchunk size) + dataSize.
            val newDataSize = existingDataSize + appendedSize
            writeInt32LEAt(raf, 4, newDataSize + 36)
            writeInt32LEAt(raf, 40, newDataSize)
        }
        Log.d(TAG, "appended ${sourceBytes.size - HEADER_SIZE} bytes onto ${target.absolutePath}")
        return true
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

    private companion object {
        const val DIR_NAME = "notes-audio"
        const val HEADER_SIZE = 44
    }
}
