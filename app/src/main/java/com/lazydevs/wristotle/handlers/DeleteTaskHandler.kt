package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.tasks.TaskMatching
import com.lazydevs.wristotle.tasks.TaskRepository

/**
 * Handles [Intent.DeleteTask] — removes a task entirely (not just
 * marks completed; the row is gone). Shares matching logic with
 * [CompleteTaskHandler] via [TaskMatching.resolve] so the user gets
 * the same disambiguation behaviour for both verbs.
 *
 * Note the PrefixHints anti-rule that keeps "delete X" / "remove X"
 * (without an explicit `task[s]` / `to-do` keyword) routing to Cancel
 * for reminders, not here. This handler only fires when the user named
 * a task context.
 *
 * Destructive — confirm gate intercepts when enabled.
 */
class DeleteTaskHandler(
    private val tasks: TaskRepository,
) : ActionHandler {

    override val tag: String = "delete-task"
    override val intent: Intent = Intent.DeleteTask

    override suspend fun handle(result: IntentResult): String {
        val target = (result.slots["target"] as? String)?.trim().orEmpty()
        if (target.isEmpty()) return "Which task?"

        val match = TaskMatching.resolve(
            target = target,
            searchPending = tasks::searchPending,
            mostRecentPending = tasks::mostRecentPending,
        )
        return when (match) {
            is TaskMatching.MatchResult.Single -> {
                tasks.delete(match.task.id)
                "Deleted: ${match.task.text}"
            }
            is TaskMatching.MatchResult.Ambiguous -> TaskMatching.renderAmbiguous(match.matches)
            is TaskMatching.MatchResult.None -> "No task matching '${match.searched}'"
            TaskMatching.MatchResult.NoPendingTasks -> "No pending tasks"
        }
    }
}
