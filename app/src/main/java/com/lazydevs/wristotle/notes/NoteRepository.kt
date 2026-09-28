// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import android.util.Log
import com.lazydevs.wristotle.notesserver.RemoteSyncHooks
import com.lazydevs.wristotle.speech.nlu.settings.AppendAudioMode
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettings
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettingsView
import kotlinx.coroutines.flow.Flow
import java.io.File

private const val TAG = "NoteRepository"

/**
 * Single facade over [NoteDao] + [NotesAudioStore] + [NoteSettings].
 *
 * Owns the keep-last-N FIFO prune so callers don't have to coordinate
 * count + audio cleanup; called after each insert and whenever the user
 * lowers the cap.
 */
class NoteRepository(
    private val dao: NoteDao,
    private val audioStore: NotesAudioStore,
    private val settings: NoteSettingsView,
) {

    /** Set when the notes-server sync is wired; null keeps notes purely local. */
    var syncHooks: RemoteSyncHooks? = null

    fun observeAll(): Flow<List<Note>> = dao.observeAllNewestFirst()

    suspend fun count(): Int = dao.count()

    /** One-shot snapshot of the [limit] newest notes — used by callers that
     *  don't want to subscribe to a Flow (e.g. the on-watch list responder). */
    suspend fun mostRecent(limit: Int): List<Note> =
        dao.notesBeyond(offset = 0, limit = limit)

    /**
     * Insert a new note. Returns the auto-generated id (later used to
     * name the per-note `.wav`). Caller is responsible for invoking
     * [attachAudio] if the dictation had a transient conversation-audio
     * file to copy. Pruning runs after insert.
     */
    suspend fun insert(body: String, source: String, createdAtEpochMs: Long): Long {
        val id = dao.insert(Note(body = body, source = source, createdAtEpochMs = createdAtEpochMs))
        prune()
        syncHooks?.onLocalCreate()
        return id
    }

    /**
     * Copies the dictation's transient `.wav` to a permanent
     * `notes-audio/<id>.wav` and writes the path on the note row.
     * No-op if the source file is gone (already FIFO-evicted by the
     * conversation-audio store).
     */
    suspend fun attachAudio(id: Long, conversationAudioFile: File) {
        if (!conversationAudioFile.exists()) {
            Log.w(TAG, "source audio gone before copy: ${conversationAudioFile.absolutePath}")
            return
        }
        val note = dao.findById(id) ?: return
        val newPath = audioStore.copyFromConversationAudio(conversationAudioFile, note.createdAtEpochMs)
            ?: return
        dao.setAudioFilePath(id, newPath)
    }

    /**
     * Append [body] to the most recently created note and bump its
     * timestamp so it surfaces at the top. Returns the updated [Note]
     * or null when there is no existing note to append to.
     *
     * Audio: when [conversationAudioFile] is supplied (watch dictation,
     * capture enabled), the appended PCM is concatenated onto the
     * existing per-note `.wav` so playback covers the whole note in
     * order. If the prior note had no audio yet, the appended dictation
     * becomes its first `.wav`.
     */
    suspend fun append(
        body: String,
        atEpochMs: Long,
        conversationAudioFile: File? = null,
    ): Note? {
        val previous = appendTarget() ?: return null
        val combined = "${previous.body}\n$body"
        dao.updateBodyAndTimestamp(previous.id, combined, atEpochMs)
        syncHooks?.onNoteAppended(previous.id, body)
        var audioPath = previous.audioFilePath
        if (conversationAudioFile != null && conversationAudioFile.exists()) {
            val prior = NoteAudioPaths.parse(previous.audioFilePath)
            val newEncoded = when (settings.appendAudioMode.value) {
                // MERGE — concatenate the new PCM onto the most recent stored
                // `.wav`. Pure-merge history stays as a single file. Mixed
                // history (mode flipped over time) grows only the last entry.
                AppendAudioMode.MERGE -> {
                    val target = prior.lastOrNull()
                    val path = audioStore.appendFromConversationAudio(
                        source = conversationAudioFile,
                        existingPath = target,
                        fallbackEpochMs = atEpochMs,
                    )
                    if (path == null) previous.audioFilePath
                    else when {
                        target == null -> NoteAudioPaths.encode(listOf(path))
                        path == target -> previous.audioFilePath
                        else -> NoteAudioPaths.encode(prior.dropLast(1) + path)
                    }
                }
                // SEPARATE — always create a new file and append its path to
                // the list. The original audio file stays untouched.
                AppendAudioMode.SEPARATE -> {
                    val path = audioStore.copyFromConversationAudio(conversationAudioFile, atEpochMs)
                    if (path == null) previous.audioFilePath
                    else NoteAudioPaths.append(previous.audioFilePath, path)
                }
            }
            if (newEncoded != previous.audioFilePath) {
                dao.setAudioFilePath(previous.id, newEncoded)
            }
            audioPath = newEncoded
        }
        return previous.copy(body = combined, createdAtEpochMs = atEpochMs, audioFilePath = audioPath)
    }

    /** Most recent note, skipping server notes that weren't written from
     *  Wristotle when the notes server is connected. */
    private suspend fun appendTarget(): Note? {
        val hooks = syncHooks?.takeIf { it.isActive } ?: return dao.findMostRecent()
        val skip = hooks.notAppendableNoteIds()
        return dao.notesBeyond(offset = 0, limit = APPEND_SCAN_LIMIT).firstOrNull { it.id !in skip }
    }

    suspend fun delete(id: Long) {
        syncHooks?.onNoteDeleting(id)
        deleteLocalOnly(id)
    }

    /** Drops the local copy only — used when the server says it's gone. */
    suspend fun deleteLocalOnly(id: Long) {
        val note = dao.findById(id) ?: return
        NoteAudioPaths.parse(note.audioFilePath).forEach(audioStore::delete)
        dao.deleteById(id)
    }

    /**
     * Deletes every note. With the notes server connected this only covers
     * notes written from Wristotle — the rest of the server's notes are
     * mirrored here but never mass-deleted from the phone.
     */
    suspend fun deleteAll() {
        val hooks = syncHooks?.takeIf { it.isActive }
        if (hooks == null) {
            audioStore.deleteAll()
            dao.deleteAll()
            return
        }
        val skip = hooks.notAppendableNoteIds()
        dao.allForBackup().filter { it.id !in skip }.forEach { delete(it.id) }
    }

    /**
     * Enforces the keep-last-N cap from [NoteSettings]. Anything beyond
     * the cap (oldest first) is deleted along with its audio file.
     * No-op when the user has [NoteSettings.UNLIMITED] selected (default).
     */
    suspend fun prune() {
        // The server holds the notes; a local cap would delete them there.
        if (syncHooks?.isActive == true) return
        val cap = settings.keepLast.value
        if (cap <= NoteSettings.UNLIMITED) return
        val total = dao.count()
        if (total <= cap) return
        // We've already ordered newest-first in the DAO; everything past
        // `cap` is what we evict. Fetch the rows so we can clean up audio
        // files too, then delete by id.
        val evict = dao.notesBeyond(offset = cap, limit = total - cap)
        evict.forEach { note ->
            NoteAudioPaths.parse(note.audioFilePath).forEach(audioStore::delete)
            dao.deleteById(note.id)
        }
        if (evict.isNotEmpty()) Log.d(TAG, "pruned ${evict.size} notes beyond cap=$cap")
    }

    private companion object {
        const val APPEND_SCAN_LIMIT = 200
    }
}
