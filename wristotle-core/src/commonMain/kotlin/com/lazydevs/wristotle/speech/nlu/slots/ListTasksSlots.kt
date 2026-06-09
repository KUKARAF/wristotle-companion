// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ListTasks].
 *
 * Optional `filter` (String): `SlotKeys.FilterCompleted` or `SlotKeys.FilterPending`. Absent
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
            COMPLETED_FILTER.containsMatchIn(lower) ->
                mapOf(SlotKeys.Filter to SlotKeys.FilterCompleted)
            PENDING_FILTER.containsMatchIn(lower) ->
                mapOf(SlotKeys.Filter to SlotKeys.FilterPending)
            else -> emptyMap() // handler defaults to pending
        }
    }

    private companion object {
        // "completed task[s]" / "done task[s]" / "finished task[s]" /
        // "tasks i('ve| have) completed|done|finished".
        val COMPLETED_FILTER = Regex(
            "(?i)(?:\\b(completed|done|finished)\\s+tasks?\\b|" +
                "\\btasks?\\s+i(?:'ve| have)\\s+(?:completed|done|finished)\\b)"
        )
        // "pending|open|remaining|outstanding task[s]".
        val PENDING_FILTER = Regex(
            "(?i)\\b(pending|open|remaining|outstanding)\\s+tasks?\\b"
        )
    }
}