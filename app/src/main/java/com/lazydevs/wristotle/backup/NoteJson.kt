package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.notes.Note
import org.json.JSONObject
import java.io.File

/**
 * Per-entity JSON (en|de)coder for [Note]. The backup format decouples
 * from Room's schema entirely: the encoder always emits the current
 * [CURRENT_SCHEMA] shape; the decoder accepts every schema from 1 up to
 * [CURRENT_SCHEMA] with explicit branches as the schema evolves.
 *
 * Path policy: `audioFilePath` is stored as a **filename only** in JSON.
 * Absolute paths under `filesDir/` are device-specific and won't survive a
 * device migration; the importer re-prefixes the basename with the local
 * `filesDir/notes-audio/<basename>` on restore.
 */
object NoteJson {

    /**
     * Schema version emitted by [encode]. Bump whenever the wire shape changes
     * (column added, renamed, type changed). The decoder must continue to
     * accept every previous schema — that's the whole point of the format.
     */
    const val CURRENT_SCHEMA = 1

    fun encode(note: Note): JSONObject = JSONObject().apply {
        put("id", note.id)
        put("body", note.body)
        put("created_at_ms", note.createdAtEpochMs)
        put("source", note.source)
        if (note.audioFilePath != null) {
            put("audio_filename", File(note.audioFilePath).name)
        }
    }

    /**
     * @param row    One row object from the data array.
     * @param schema The wrapper's `schema:` field — selects the version branch.
     * @return Decoded [Note] with `audioFilePath` set to the filename only;
     *         the caller (importer) rewrites it to the local absolute path
     *         once it has copied the audio file into `filesDir/notes-audio/`.
     */
    fun decode(row: JSONObject, schema: Int): Note {
        return when (schema) {
            1 -> Note(
                id = row.optLong("id", 0L),
                body = row.optString("body", ""),
                createdAtEpochMs = row.optLong("created_at_ms", 0L),
                source = row.optString("source", "unknown"),
                audioFilePath = row.optString("audio_filename").takeIf { it.isNotEmpty() },
            )
            else -> throw IllegalArgumentException(
                "Unsupported NoteJson schema $schema (max $CURRENT_SCHEMA)"
            )
        }
    }
}
