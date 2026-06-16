// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.util.Log
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.optStringSlot
import java.io.File

private const val TAG = "NoteHandler"

/**
 * Handles [Intent.Note] — persists a free-form note from a watch dictation.
 *
 * Slot contract (from NoteSlots + PebbleListenerService):
 *   - `body: String` (required) — the note text, lead-in already stripped.
 *   - `audioPath: String?` (optional) — absolute path to the dictation's
 *     transient conversation-audio `.wav`. When present, the handler asks
 *     the repository to copy it into permanent notes-audio/.
 *
 * Response is short on purpose since the watch echoes it back as the
 * dictation result.
 */
class NoteHandler(private val notes: NoteRepository) : ActionHandler {

    override val tag = "note"
    override val intent = Intent.Note
    override val cardKind: String? = "note_create"

    override suspend fun handle(result: IntentResult): String {
        val body = result.optStringSlot(SlotKeys.Body)?.takeIf { it.isNotBlank() }
            ?: result.rawQuery.trim()
        if (body.isBlank()) return "Couldn't capture an empty note"

        val now = System.currentTimeMillis()
        val id = notes.insert(body = body, source = SOURCE_WATCH, createdAtEpochMs = now)
        Log.d(TAG, "saved note id=$id len=${body.length}")

        result.optStringSlot(SlotKeys.AudioPath)?.let { path ->
            runCatching { notes.attachAudio(id, File(path)) }
                .onFailure { Log.w(TAG, "attachAudio failed", it) }
        }

        return "Noted"
    }

    companion object {
        const val SOURCE_WATCH = "watch"
    }
}