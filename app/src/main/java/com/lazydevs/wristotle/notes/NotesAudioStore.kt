// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import android.content.Context
import android.util.Log
import java.io.File
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
class NotesAudioStore internal constructor(val dir: File) {

    /** Production constructor — resolves the per-note audio dir under filesDir. */
    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

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

    /** Thin Android-side wrapper around the pure [appendWav] for logging. */
    private fun appendWav(target: File, source: File): Boolean {
        val appended = com.lazydevs.wristotle.notes.appendWav(target, source)
        Log.d(TAG, "appended $appended bytes onto ${target.absolutePath}")
        return true
    }

    private companion object {
        const val DIR_NAME = "notes-audio"
    }
}