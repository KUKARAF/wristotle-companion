package com.lazydevs.wristotle.tasks

/**
 * Pure-function formatter for the *"what are my tasks"* watch chat
 * reply. The watch chat is ~5 short lines tall, so we render the first
 * [MAX_INLINE] pending tasks numbered + a `(+N more)` suffix when more
 * exist.
 *
 * Tested as a pure function — no DB, no Context. Kept out of
 * [com.lazydevs.wristotle.handlers.ListTasksHandler] so its formatting
 * logic can evolve under unit tests without dragging the handler.
 */
object TasksResponseFormatter {

    /** How many tasks to inline before falling back to "(+N more)". */
    const val MAX_INLINE: Int = 5

    fun format(pending: List<TaskEntity>): String {
        if (pending.isEmpty()) return "No tasks"

        val shown = pending.take(MAX_INLINE)
        val remaining = pending.size - shown.size
        val lines = shown.mapIndexed { idx, t -> "${idx + 1}. ${t.text}" }
        val body = lines.joinToString("\n")
        return if (remaining > 0) "$body\n(+$remaining more)" else body
    }
}
