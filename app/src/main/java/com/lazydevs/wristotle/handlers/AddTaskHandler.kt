package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.tasks.TaskRepository

/**
 * Handles [Intent.AddTask] — creates a new pending task in the tasks
 * Room store and replies with a one-line confirmation that fits on the
 * watch chat.
 *
 * Slot required: `body` (the task text, lead-in stripped by
 * [com.lazydevs.wristotle.nlu.slots.AddTaskSlots]). Returns *"What's
 * the task?"* if body is missing/blank — the user can retry with
 * something parseable rather than seeing a silent failure.
 *
 * `source` is set to `"watch"` for now; Phase C will add
 * `"companion_typed"` and `"companion_voice"` as additional values
 * when the Tasks tab gains its inline composer.
 */
class AddTaskHandler(
    private val tasks: TaskRepository,
) : ActionHandler {

    override val tag: String = "add-task"
    override val intent: Intent = Intent.AddTask

    override suspend fun handle(result: IntentResult): String {
        val body = (result.slots["body"] as? String)?.trim().orEmpty()
        if (body.isEmpty()) return "What's the task?"

        tasks.add(text = body, source = "watch")
        return "Added: $body"
    }
}
