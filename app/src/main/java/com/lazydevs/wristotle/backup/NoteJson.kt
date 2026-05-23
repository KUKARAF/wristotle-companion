package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteAudioPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-entity JSON (en|de)coder for [Note]. The backup format decouples
 * from Room's schema entirely: the encoder always emits the current
 * [CURRENT_SCHEMA] shape; the decoder accepts every schema from 1 up to
 * [CURRENT_SCHEMA] with explicit branches as the schema evolves.
 *
 * Path policy: `audioFilePath` may carry ONE OR MORE paths in
 * NoteAudioPaths.SEP-joined form (separate-append mode produces multiple
 * audio files per note; merge-append keeps a single concatenated file).
 * The JSON shape is always an array of **filename-only** entries under
 * `audio_filenames`; the importer re-prefixes each basename with the local
 * `filesDir/notes-audio/<basename>` and US-joins them back into a single
 * column value. A decoded row's transient `audioFilePath` carries the
 * SEP-joined basenames until the importer rewrites to absolute paths.
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
        val paths = NoteAudioPaths.parse(note.audioFilePath)
        if (paths.isNotEmpty()) {
            val arr = JSONArray()
            for (p in paths) arr.put(File(p).name)
            put("audio_filenames", arr)
        }
    }

    /**
     * @param row    One row object from the data array.
     * @param schema The wrapper's `schema:` field — selects the version branch.
     * @return Decoded [Note] whose `audioFilePath` is the SEP-joined list of
     *         basenames extracted from JSON. The caller (importer) rewrites
     *         each basename to the final on-device absolute path and rejoins.
     *
     * For schema 1, both shapes are accepted: `audio_filenames` (array — the
     * shape emitted by the current encoder) and `audio_filename` (single
     * string — a pre-multi-path-aware encoder that earlier dev builds shipped
     * locally). New encoders only emit the array form.
     */
    fun decode(row: JSONObject, schema: Int): Note {
        return when (schema) {
            1 -> {
                val basenames = readBasenames(row)
                Note(
                    id = row.optLong("id", 0L),
                    body = row.optString("body", ""),
                    createdAtEpochMs = row.optLong("created_at_ms", 0L),
                    source = row.optString("source", "unknown"),
                    audioFilePath = NoteAudioPaths.encode(basenames),
                )
            }
            else -> throw IllegalArgumentException(
                "Unsupported NoteJson schema $schema (max $CURRENT_SCHEMA)"
            )
        }
    }

    private fun readBasenames(row: JSONObject): List<String> {
        val arr = row.optJSONArray("audio_filenames")
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotEmpty() }
            }
        }
        // Legacy single-path form, from pre-multi-path encoders.
        val single = row.optString("audio_filename")
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }
}
