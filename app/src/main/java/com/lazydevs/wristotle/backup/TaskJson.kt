package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.tasks.TaskEntity
import org.json.JSONObject

/**
 * Per-entity JSON (en|de)coder for [TaskEntity]. Mirrors the
 * [NoteJson] contract: encoder always emits the current
 * [CURRENT_SCHEMA] shape; decoder accepts every schema from 1 up to
 * [CURRENT_SCHEMA] with explicit branches as the schema evolves.
 *
 * Tasks have no audio assets — the encoder is therefore simpler than
 * Note's; just the row's columns mapped to snake-case keys. The
 * `completed_at_ms` field is optional (omitted for pending tasks) so
 * the JSON stays compact for the common case.
 */
object TaskJson {

    /**
     * Schema version emitted by [encode]. Bump whenever the wire
     * shape changes (column added, renamed, type changed). The
     * decoder must continue to accept every previous schema.
     */
    const val CURRENT_SCHEMA = 1

    fun encode(task: TaskEntity): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("text", task.text)
        put("completed", task.completed)
        put("created_at_ms", task.createdAtEpochMs)
        if (task.completedAtEpochMs != null) put("completed_at_ms", task.completedAtEpochMs)
        put("source", task.source)
    }

    /**
     * @param row    One row object from the data array.
     * @param schema The wrapper's `schema:` field — selects the version branch.
     */
    fun decode(row: JSONObject, schema: Int): TaskEntity {
        return when (schema) {
            1 -> TaskEntity(
                id = row.optLong("id", 0L),
                text = row.optString("text", ""),
                completed = row.optBoolean("completed", false),
                createdAtEpochMs = row.optLong("created_at_ms", 0L),
                completedAtEpochMs = if (row.has("completed_at_ms"))
                    row.getLong("completed_at_ms") else null,
                source = row.optString("source", "unknown"),
            )
            else -> throw IllegalArgumentException(
                "Unsupported TaskJson schema $schema (max $CURRENT_SCHEMA)"
            )
        }
    }
}
