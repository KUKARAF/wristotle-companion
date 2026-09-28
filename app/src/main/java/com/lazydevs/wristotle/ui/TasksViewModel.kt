// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.tasks.TaskEntity
import com.lazydevs.wristotle.tasks.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Tasks tab. Pure CRUD façade over [TaskRepository] —
 * substantially simpler than [NotesViewModel] because tasks have no
 * dictation, no audio, no retention setting.
 *
 * Two live StateFlows feed the screen:
 *   - [pending] — the active checklist (newest-first)
 *   - [completed] — the collapsible "Completed" section
 *
 * Each mutating operation kicks off a launch in [viewModelScope] —
 * the StateFlows observe Room and re-emit automatically once the
 * write commits.
 */
class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: TaskRepository =
        (app as WristotleApplication).taskRepository

    private val notesServer = (app as WristotleApplication).notesServerSync

    /** Signed in to notes.osmosis.page — the list mirrors the server then. */
    val serverConnected: StateFlow<Boolean> =
        (app as WristotleApplication).notesServerAuth.account.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Pull from the notes server when the screen opens (no-op when signed out). */
    fun refreshFromServer() = viewModelScope.launch {
        notesServer.syncIfStale(maxAgeMs = 10_000, timeoutMs = 30_000)
    }

    val pending: StateFlow<List<TaskEntity>> =
        repository.observePending()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completed: StateFlow<List<TaskEntity>> =
        repository.observeCompleted()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Insert a new pending task from the inline composer. No-op when
     *  [text] is blank — the screen's Add button stays disabled in that
     *  case, but the guard here protects against trailing-whitespace
     *  edge cases. */
    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.add(text = trimmed, source = SOURCE_TYPED)
        }
    }

    fun complete(id: Long) = viewModelScope.launch { repository.markCompleted(id) }

    /** Re-open a completed task — moves it back to the Pending section. */
    fun reopen(id: Long) = viewModelScope.launch { repository.markPending(id) }

    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }

    /** Permanently clear ALL tasks (both pending + completed). Used by
     *  the "Clear all" affordance on the screen. */
    fun deleteAll() = viewModelScope.launch { repository.deleteAll() }

    private companion object {
        const val SOURCE_TYPED = "companion_typed"
    }
}