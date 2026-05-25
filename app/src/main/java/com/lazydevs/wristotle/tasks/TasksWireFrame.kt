package com.lazydevs.wristotle.tasks

import com.lazydevs.wristotle.transport.MessageKeys

/**
 * Encodes / decodes the [MessageKeys.TASKS_RESPONSE] CSTRING payload —
 * the framed list of pending tasks the watch's tasks window renders.
 *
 * **Wire format:**
 * ```
 * <id1><US><text1><RS><id2><US><text2><RS>…<idN><US><textN>
 * ```
 * - `<US>` = `0x1F` ([MessageKeys.TASKS_UNIT_SEPARATOR]) — between the
 *   id and text of a single task.
 * - `<RS>` = `0x1E` ([MessageKeys.TASKS_RECORD_SEPARATOR]) — between
 *   tasks. No trailing RS.
 *
 * Both separators are non-printable C0 control characters that never
 * appear in user text, so the split is unambiguous without escaping.
 *
 * **Budget.** The CSTRING is capped at [MessageKeys.TASKS_RESPONSE_MAX_CHARS]
 * to fit Pebble's AppMessage outbox. [encode] truncates the LIST (drops
 * older tasks past the budget) rather than truncating an individual
 * task's text — a half-shown task name is worse UX than fewer tasks.
 *
 * Pure function (no Android Context, no DAO) — unit-testable.
 */
object TasksWireFrame {

    /** Build the TASKS_RESPONSE payload for [tasks], capped at the
     *  outbox-friendly budget. Tasks beyond the cap are dropped
     *  silently (caller-supplied ordering preserved).
     *
     *  Each task is encoded as `<id><US><state><US><text>`, with `state`
     *  the literal `"0"` (pending) or `"1"` (completed). Records joined
     *  by the record-separator; no trailing separator. */
    fun encode(tasks: List<TaskEntity>, budget: Int = MessageKeys.TASKS_RESPONSE_MAX_CHARS): String {
        val sb = StringBuilder()
        for (t in tasks) {
            val state = if (t.completed) '1' else '0'
            val record = buildString {
                append(t.id)
                append(MessageKeys.TASKS_UNIT_SEPARATOR)
                append(state)
                append(MessageKeys.TASKS_UNIT_SEPARATOR)
                append(t.text)
            }
            // +1 for the trailing record-separator we'd add if this
            // isn't the first task in the buffer.
            val needed = if (sb.isEmpty()) record.length else record.length + 1
            if (sb.length + needed > budget) break
            if (sb.isNotEmpty()) sb.append(MessageKeys.TASKS_RECORD_SEPARATOR)
            sb.append(record)
        }
        return sb.toString()
    }

    /** Decoded view of a single wire-frame task — used by tests. */
    data class Decoded(val id: Long, val completed: Boolean, val text: String)

    /** Inverse of [encode] — used by tests to round-trip a payload. */
    fun decode(payload: String): List<Decoded> {
        if (payload.isEmpty()) return emptyList()
        return payload.split(MessageKeys.TASKS_RECORD_SEPARATOR).mapNotNull { record ->
            val parts = record.split(MessageKeys.TASKS_UNIT_SEPARATOR, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val completed = parts[1] == "1"
            Decoded(id, completed, parts[2])
        }
    }
}
