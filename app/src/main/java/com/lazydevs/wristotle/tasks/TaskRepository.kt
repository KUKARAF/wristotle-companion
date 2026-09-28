// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tasks

import com.lazydevs.wristotle.notesserver.RemoteSyncHooks
import kotlinx.coroutines.flow.Flow

/**
 * Façade over [TaskDao] — keeps the handlers / view-models free of Room
 * imports and gives one spot to add cross-cutting concerns (logging,
 * audio, audit) later.
 *
 * v1 is intentionally thin — no caching, no business logic. The DAO
 * methods are surfaced as-is with handler-friendly names.
 */
class TaskRepository(private val dao: TaskDao) {

    /** Set when the notes-server sync is wired; null keeps tasks purely local. */
    var syncHooks: RemoteSyncHooks? = null

    /** Live stream of pending tasks for the Tasks tab + watch list view. */
    fun observePending(): Flow<List<TaskEntity>> = dao.observePending()

    /** Live stream of completed tasks for the Tasks tab's collapsible section. */
    fun observeCompleted(): Flow<List<TaskEntity>> = dao.observeCompleted()

    /** All tasks (pending first) as a Flow — backs the folder-sync export. */
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    /**
     * Create a new pending task. Returns the row id (used by the watch
     * "task added — id N" path in Phase B).
     */
    suspend fun add(text: String, source: String, nowMs: Long = System.currentTimeMillis()): Long {
        val id = dao.insert(
            TaskEntity(
                text = text,
                completed = false,
                createdAtEpochMs = nowMs,
                completedAtEpochMs = null,
                source = source,
            )
        )
        syncHooks?.onLocalCreate()
        return id
    }

    /** Pull fresh tasks from the notes server first (bounded; no-op when signed out). */
    suspend fun refreshFromServer(timeoutMs: Long = 3_000) {
        syncHooks?.refreshIfStale(timeoutMs)
    }

    /** Snapshot of pending tasks, newest first. */
    suspend fun listPending(): List<TaskEntity> = dao.listPending()

    /** Snapshot of completed tasks, most-recently-completed first. */
    suspend fun listCompleted(): List<TaskEntity> = dao.listCompleted()

    /** Pending first, then completed. Backs the watch's "All" view. */
    suspend fun listAll(): List<TaskEntity> = dao.listAll()

    /** Substring search over pending tasks (case-insensitive). */
    suspend fun searchPending(needle: String): List<TaskEntity> =
        dao.searchPending(needle.lowercase())

    /** Most-recently-created pending task — backs the "last task" shortcut. */
    suspend fun mostRecentPending(): TaskEntity? = dao.findMostRecentPending()

    suspend fun findById(id: Long): TaskEntity? = dao.findById(id)

    suspend fun markCompleted(id: Long, nowMs: Long = System.currentTimeMillis()) {
        dao.markCompleted(id, nowMs)
        syncHooks?.onTaskDoneChanged(id, done = true)
    }

    suspend fun markPending(id: Long) {
        dao.markPending(id)
        syncHooks?.onTaskDoneChanged(id, done = false)
    }

    suspend fun delete(id: Long) {
        syncHooks?.onTaskDeleting(id)
        dao.deleteById(id)
    }

    /** With the notes server connected, "clear all" removes the tasks
     *  shown here from their daily notes one by one. */
    suspend fun deleteAll() {
        if (syncHooks?.isActive == true) {
            dao.listAll().forEach { delete(it.id) }
            return
        }
        dao.deleteAll()
    }

    suspend fun countPending(): Int = dao.countPending()

    /** Full snapshot for backup export (Phase D). */
    suspend fun allForBackup(): List<TaskEntity> = dao.allForBackup()
}