package com.lazydevs.wristotle.tasks

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

    /** Live stream of pending tasks for the Tasks tab + watch list view. */
    fun observePending(): Flow<List<TaskEntity>> = dao.observePending()

    /** Live stream of completed tasks for the Tasks tab's collapsible section. */
    fun observeCompleted(): Flow<List<TaskEntity>> = dao.observeCompleted()

    /**
     * Create a new pending task. Returns the row id (used by the watch
     * "task added — id N" path in Phase B).
     */
    suspend fun add(text: String, source: String, nowMs: Long = System.currentTimeMillis()): Long =
        dao.insert(
            TaskEntity(
                text = text,
                completed = false,
                createdAtEpochMs = nowMs,
                completedAtEpochMs = null,
                source = source,
            )
        )

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
    }

    suspend fun markPending(id: Long) {
        dao.markPending(id)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun countPending(): Int = dao.countPending()

    /** Full snapshot for backup export (Phase D). */
    suspend fun allForBackup(): List<TaskEntity> = dao.allForBackup()
}
