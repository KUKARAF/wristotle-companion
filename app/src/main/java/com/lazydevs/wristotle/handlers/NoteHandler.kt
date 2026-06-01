package com.lazydevs.wristotle.handlers

import android.util.Log
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
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

    override suspend fun handle(result: IntentResult): String {
        val body = (result.slots[SlotKeys.Body] as? String)?.takeIf { it.isNotBlank() }
            ?: result.rawQuery.trim()
        if (body.isBlank()) return "Couldn't capture an empty note"

        val now = System.currentTimeMillis()
        val id = notes.insert(body = body, source = SOURCE_WATCH, createdAtEpochMs = now)
        Log.d(TAG, "saved note id=$id len=${body.length}")

        (result.slots[SlotKeys.AudioPath] as? String)?.let { path ->
            runCatching { notes.attachAudio(id, File(path)) }
                .onFailure { Log.w(TAG, "attachAudio failed", it) }
        }

        return "Noted"
    }

    companion object {
        const val SOURCE_WATCH = "watch"
    }
}
