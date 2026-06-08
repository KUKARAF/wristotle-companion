// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.util.Log
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.io.File

private const val TAG = "AppendNoteHandler"

/**
 * Handles [Intent.AppendNote] — extends the most recently created note
 * with new text instead of creating a fresh one ("add to my previous
 * note …", "append to the last note …", "append …").
 *
 * Slot contract (from AppendNoteSlots):
 *   - `body: String` (required) — the text to append.
 *
 * When there is no existing note, falls back to an instructive failure
 * response rather than silently creating a new one — the user explicitly
 * asked to append.
 */
class AppendNoteHandler(private val notes: NoteRepository) : ActionHandler {

    override val tag = "note.append"
    override val intent = Intent.AppendNote

    override suspend fun handle(result: IntentResult): String {
        val body = (result.slots[SlotKeys.Body] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Couldn't capture an empty note"
        val audioFile = (result.slots[SlotKeys.AudioPath] as? String)?.let(::File)
        val updated = notes.append(
            body = body,
            atEpochMs = System.currentTimeMillis(),
            conversationAudioFile = audioFile,
        )
        return if (updated != null) {
            Log.d(TAG, "appended to note id=${updated.id} newLen=${updated.body.length}")
            "Added to previous note"
        } else {
            "No previous note to add to"
        }
    }
}