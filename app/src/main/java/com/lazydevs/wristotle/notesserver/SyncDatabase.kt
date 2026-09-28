// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 * append). Note/task creates are not queued — an unlinked local row *is*
 * the op.
 *
 * Task ops stay queued after they're applied ([verifyUntilMs] > 0) until
 * `/api/todos` — which reads the live web-editor copy — shows the change.
 * If the daily note is open in rust_note's editor, the editor's autosave
 * overwrites our REST write; the op is then re-applied (idempotently) on
 * later syncs until it sticks or the window runs out.
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
    /** 0 = not applied yet; otherwise applied and awaiting confirmation until this time. */
    @ColumnInfo(defaultValue = "0") val verifyUntilMs: Long = 0,
    /** TASK_CREATE: the checkbox state the line was written with. */
    @ColumnInfo(defaultValue = "0") val done: Boolean = false,
) {
    companion object {
        const val NOTE_APPEND = "note_append"
        const val NOTE_DELETE = "note_delete"
        const val TASK_DONE = "task_done"
        const val TASK_REOPEN = "task_reopen"
        const val TASK_DELETE = "task_delete"
        const val TASK_CREATE = "task_create"
        val TASK_KINDS = setOf(TASK_DONE, TASK_REOPEN, TASK_DELETE, TASK_CREATE)
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

    @Update
    suspend fun updateOp(op: PendingOp)

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
    version = 2,
    exportSchema = false,
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        fun build(context: Context): SyncDatabase =
            Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "wristotle-notes-server.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_ops ADD COLUMN verifyUntilMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_ops ADD COLUMN done INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
