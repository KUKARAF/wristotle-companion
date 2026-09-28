// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Local note row ↔ server note id. A local note without a link has never
 * been uploaded (created offline, migrated from pre-server data, or
 * restored from a backup) and is pushed on the next sync.
 */
@Entity(tableName = "note_links")
data class NoteLink(
    @PrimaryKey val localId: Long,
    val remoteId: String,
    /** Server `updated_at` (epoch ms) of the content we last mirrored. */
    val remoteUpdatedAtMs: Long,
)

/**
 * Local task row ↔ checkbox line in a server note. [line] is 1-based and
 * only a hint — lines shift as notes are edited, so [rawText] (the text
 * after the `]`, verbatim) is what we match on.
 */
@Entity(tableName = "task_links")
data class TaskLink(
    @PrimaryKey val localId: Long,
    val noteId: String,
    val line: Int,
    val rawText: String,
)

/**
 * Queued remote mutation that can't be derived from local state alone
 * (the local row may already be gone, or the op is a delta like an
 * append). Creates are not queued — an unlinked local row *is* the op.
 */
@Entity(tableName = "pending_ops")
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val noteId: String,
    val line: Int = 0,
    val rawText: String = "",
    val text: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val NOTE_APPEND = "note_append"
        const val NOTE_DELETE = "note_delete"
        const val TASK_DONE = "task_done"
        const val TASK_REOPEN = "task_reopen"
        const val TASK_DELETE = "task_delete"
    }
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM note_links")
    suspend fun noteLinks(): List<NoteLink>

    @Query("SELECT * FROM note_links WHERE localId = :localId")
    suspend fun noteLink(localId: Long): NoteLink?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNoteLink(link: NoteLink)

    @Query("DELETE FROM note_links WHERE localId = :localId")
    suspend fun deleteNoteLink(localId: Long)

    @Query("SELECT * FROM task_links")
    suspend fun taskLinks(): List<TaskLink>

    @Query("SELECT * FROM task_links WHERE localId = :localId")
    suspend fun taskLink(localId: Long): TaskLink?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTaskLink(link: TaskLink)

    @Query("DELETE FROM task_links WHERE localId = :localId")
    suspend fun deleteTaskLink(localId: Long)

    @Insert
    suspend fun enqueue(op: PendingOp): Long

    @Query("SELECT * FROM pending_ops ORDER BY id")
    suspend fun pendingOps(): List<PendingOp>

    @Query("SELECT COUNT(*) FROM pending_ops")
    suspend fun pendingOpCount(): Int

    @Query("DELETE FROM pending_ops WHERE id IN (:ids)")
    suspend fun deleteOps(ids: List<Long>)

    @Query("DELETE FROM note_links")
    suspend fun clearNoteLinks()

    @Query("DELETE FROM task_links")
    suspend fun clearTaskLinks()

    @Query("DELETE FROM pending_ops")
    suspend fun clearOps()
}

@Database(
    entities = [NoteLink::class, TaskLink::class, PendingOp::class],
    version = 1,
    exportSchema = false,
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        fun build(context: Context): SyncDatabase =
            Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "wristotle-notes-server.db")
                .build()
    }
}
