package com.lazydevs.wristotle.tasks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    /** Pending tasks, newest first. Flow so the Tasks tab updates live. */
    @Query("SELECT * FROM tasks WHERE completed = 0 ORDER BY createdAtEpochMs DESC")
    fun observePending(): Flow<List<TaskEntity>>

    /** Completed tasks, most-recently-completed first. */
    @Query("SELECT * FROM tasks WHERE completed = 1 ORDER BY completedAtEpochMs DESC")
    fun observeCompleted(): Flow<List<TaskEntity>>

    /** One-shot snapshot of pending tasks — used by ListTasks voice command. */
    @Query("SELECT * FROM tasks WHERE completed = 0 ORDER BY createdAtEpochMs DESC")
    suspend fun listPending(): List<TaskEntity>

    /**
     * Most-recently-created pending task. Backs the *"complete the last
     * task"* / *"delete my latest task"* shortcut so users can act on a
     * task they just added without restating its text.
     */
    @Query("SELECT * FROM tasks WHERE completed = 0 ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun findMostRecentPending(): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TaskEntity?

    /** Substring search over pending tasks (case-insensitive). Drives the
     *  complete/delete matching path. SQLite LIKE is already
     *  case-insensitive for ASCII; query is lowercased by the caller
     *  for paranoid coverage. */
    @Query("SELECT * FROM tasks WHERE completed = 0 AND LOWER(text) LIKE '%' || :needle || '%' ORDER BY createdAtEpochMs DESC")
    suspend fun searchPending(needle: String): List<TaskEntity>

    @Query("UPDATE tasks SET completed = 1, completedAtEpochMs = :timestamp WHERE id = :id")
    suspend fun markCompleted(id: Long, timestamp: Long)

    /** Re-open a completed task (Tasks tab swipe-back / Phase C). */
    @Query("UPDATE tasks SET completed = 0, completedAtEpochMs = NULL WHERE id = :id")
    suspend fun markPending(id: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM tasks WHERE completed = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun count(): Int

    /** Full snapshot of every task for backup export (Phase D). Ordered
     *  by id so the resulting JSON is deterministic across exports. */
    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun allForBackup(): List<TaskEntity>
}
