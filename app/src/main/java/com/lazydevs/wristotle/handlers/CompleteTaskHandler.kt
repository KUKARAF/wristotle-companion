package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.tasks.TaskMatching
import com.lazydevs.wristotle.tasks.TaskRepository

/**
 * Handles [Intent.CompleteTask] — marks a pending task done.
 *
 * Resolution flow ([TaskMatching.resolve]):
 *   - `target` empty → *"Which task?"*
 *   - "last task" shortcut → most-recently-created pending task
 *   - Substring match: 0 → *"No task matching X"*, 1 → mark complete,
 *     2+ → disambiguation reply.
 *
 * Destructive ([com.lazydevs.wristotle.handlers.requiresConfirm] = true)
 * so the confirm-before-dispatch gate intercepts when enabled.
 */
class CompleteTaskHandler(
    private val tasks: TaskRepository,
) : ActionHandler {

    override val tag: String = "complete-task"
    override val intent: Intent = Intent.CompleteTask

    override suspend fun handle(result: IntentResult): String {
        val target = (result.slots[SlotKeys.Target] as? String)?.trim().orEmpty()
        if (target.isEmpty()) return "Which task?"

        val match = TaskMatching.resolve(
            target = target,
            searchPending = tasks::searchPending,
            mostRecentPending = tasks::mostRecentPending,
        )
        return when (match) {
            is TaskMatching.MatchResult.Single -> {
                tasks.markCompleted(match.task.id)
                "Completed: ${match.task.text}"
            }
            is TaskMatching.MatchResult.Ambiguous -> TaskMatching.renderAmbiguous(match.matches)
            is TaskMatching.MatchResult.None -> "No task matching '${match.searched}'"
            TaskMatching.MatchResult.NoPendingTasks -> "No pending tasks"
        }
    }
}
