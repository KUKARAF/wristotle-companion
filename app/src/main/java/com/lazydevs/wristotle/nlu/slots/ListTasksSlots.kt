package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ListTasks].
 *
 * Optional `filter` (String): `"completed"` or `"pending"`. Absent
 * defaults to pending — the common case (*"what are my tasks"*,
 * *"list my tasks"*).
 *
 * Detected when the query contains the filter keyword adjacent to the
 * tasks noun:
 *  - `"completed tasks"` / `"done tasks"` / `"finished tasks"` → completed
 *  - `"pending tasks"` / `"open tasks"` / `"remaining tasks"` → pending
 *  - `"all tasks"` → pending (no separate "all" support in v1; we
 *    show pending as the most useful default)
 *
 * Kept tight on purpose — adding a filter for arbitrary verbs ("show
 * me my finished work") would risk false positives. The narrow keyword
 * set covers what the user actually says.
 */
class ListTasksSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val lower = query.lowercase()
        return when {
            COMPLETED_KEYWORDS.any { lower.contains(it) } -> mapOf("filter" to "completed")
            PENDING_KEYWORDS.any { lower.contains(it) }   -> mapOf("filter" to "pending")
            else -> emptyMap() // handler defaults to pending
        }
    }

    private companion object {
        val COMPLETED_KEYWORDS = listOf(
            "completed task", "completed tasks",
            "done task", "done tasks",
            "finished task", "finished tasks",
            // Past-tense filler the user might say after the noun.
            "tasks i've completed", "tasks i have completed",
            "tasks i've done", "tasks i have done",
            "tasks i've finished", "tasks i have finished",
        )
        val PENDING_KEYWORDS = listOf(
            "pending task", "pending tasks",
            "open task", "open tasks",
            "remaining task", "remaining tasks",
            "outstanding task", "outstanding tasks",
        )
    }
}
