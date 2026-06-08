// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.tasks.TaskRepository
import com.lazydevs.wristotle.tasks.TasksResponseFormatter

/**
 * Handles [Intent.ListTasks] — reads the pending tasks from the Room
 * store and returns the [TasksResponseFormatter] string for the watch
 * chat ("1. buy milk / 2. call dentist / (+3 more)").
 *
 * No slots used. The Phase B watch tasks-list module bypasses this
 * handler and reads the framed list via [TASKS_REQUEST] /
 * [TASKS_RESPONSE] AppMessage keys directly — this handler is only the
 * voice-via-chat path.
 */
class ListTasksHandler(
    private val tasks: TaskRepository,
) : ActionHandler {

    override val tag: String = "list-tasks"
    override val intent: Intent = Intent.ListTasks

    override suspend fun handle(result: IntentResult): String {
        // `filter` slot (set by ListTasksSlots) selects pending vs
        // completed. Absent → pending (default — most common case).
        return when ((result.slots[SlotKeys.Filter] as? String)?.lowercase()) {
            SlotKeys.FilterCompleted -> TasksResponseFormatter.format(
                tasks = tasks.listCompleted(),
                emptyMessage = "No completed tasks",
            )
            else -> TasksResponseFormatter.format(
                tasks = tasks.listPending(),
                emptyMessage = "No tasks",
            )
        }
    }
}