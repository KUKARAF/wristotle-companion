package com.lazydevs.wristotle.tasks

/**
 * Resolves a spoken `target` string into a specific [TaskEntity] from
 * the user's pending list. Shared between [com.lazydevs.wristotle.handlers.CompleteTaskHandler]
 * and [com.lazydevs.wristotle.handlers.DeleteTaskHandler] so the
 * matching rules + error responses stay identical across verbs.
 *
 * Strategy:
 *  1. **"Last task" shortcut.** If `target` is one of [LAST_TASK_KEYWORDS]
 *     ("last", "latest", "most recent", "the last one", …), pick the
 *     most-recently-created pending task. Lets the user complete /
 *     delete a task they just added without restating its text.
 *  2. **Substring case-insensitive match** over pending tasks. The
 *     repository's `searchPending` does the SQL LIKE.
 *  3. **0 matches** → [MatchResult.None] with the searched term.
 *  4. **1 match** → [MatchResult.Single].
 *  5. **2+ matches** → [MatchResult.Ambiguous] with the candidate list
 *     so the handler can render a disambiguation reply.
 *
 * Kept pure (no Android Context, no DAO) so the resolver itself is
 * unit-testable. Callers inject the repo's `searchPending` +
 * `mostRecentPending` as suspend lambdas.
 */
object TaskMatching {

    sealed class MatchResult {
        data class Single(val task: TaskEntity) : MatchResult()
        data class Ambiguous(val matches: List<TaskEntity>) : MatchResult()
        data class None(val searched: String) : MatchResult()
        /** Last-task shortcut requested but there are no pending tasks. */
        object NoPendingTasks : MatchResult()
    }

    /** Keywords that bypass text matching and pick the most-recently
     *  created pending task. Case- and punctuation-stripped before
     *  the comparison. */
    val LAST_TASK_KEYWORDS: Set<String> = setOf(
        "last", "the last", "the last one", "the last task",
        "latest", "the latest", "the latest one", "the latest task",
        "most recent", "the most recent", "the most recent task",
        "most recently added", "the most recently added",
        "last one", "latest one",
        "my last task", "my latest task", "my most recent task",
    )

    suspend fun resolve(
        target: String,
        searchPending: suspend (String) -> List<TaskEntity>,
        mostRecentPending: suspend () -> TaskEntity?,
    ): MatchResult {
        val normalised = target.lowercase().trim().trim('.', ',', ':', ';', '!', '?', '-').trim()
        if (normalised.isEmpty()) return MatchResult.None(target)

        if (normalised in LAST_TASK_KEYWORDS) {
            val recent = mostRecentPending() ?: return MatchResult.NoPendingTasks
            return MatchResult.Single(recent)
        }

        val matches = searchPending(normalised)
        return when (matches.size) {
            0 -> MatchResult.None(normalised)
            1 -> MatchResult.Single(matches.first())
            else -> MatchResult.Ambiguous(matches)
        }
    }

    /** Watch-chat-friendly rendering of an [MatchResult.Ambiguous] list —
     *  up to first 3 candidates inlined, suffix when more exist. */
    fun renderAmbiguous(matches: List<TaskEntity>, maxInline: Int = 3): String {
        val shown = matches.take(maxInline)
        val remaining = matches.size - shown.size
        val names = shown.joinToString(", ") { it.text }
        val suffix = if (remaining > 0) " (+$remaining more)" else ""
        return "Multiple matches: $names$suffix — which one?"
    }
}
