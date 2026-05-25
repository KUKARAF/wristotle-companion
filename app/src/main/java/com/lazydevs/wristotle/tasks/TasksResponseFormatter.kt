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

    fun format(pending: List<TaskEntity>): String = format(pending, emptyMessage = "No tasks")

    /**
     * Same as [format] but with a caller-supplied [emptyMessage] for
     * the zero-task case. Lets the ListTasks handler render *"No
     * completed tasks"* vs *"No tasks"* depending on which filter the
     * user asked for, without the formatter knowing about filter
     * semantics.
     */
    fun format(tasks: List<TaskEntity>, emptyMessage: String): String {
        if (tasks.isEmpty()) return emptyMessage

        val shown = tasks.take(MAX_INLINE)
        val remaining = tasks.size - shown.size
        val lines = shown.mapIndexed { idx, t -> "${idx + 1}. ${t.text}" }
        val body = lines.joinToString("\n")
        return if (remaining > 0) "$body\n(+$remaining more)" else body
    }
}
