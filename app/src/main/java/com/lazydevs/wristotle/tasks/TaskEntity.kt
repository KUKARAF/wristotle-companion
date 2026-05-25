package com.lazydevs.wristotle.tasks

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One checklist item. Tasks are long-term user data — kept until the
 * user deletes them. Completed tasks stay in the DB by default (the
 * Tasks tab shows them under a collapsible "Completed" section) so
 * the user can review or re-open them; nothing auto-prunes.
 *
 * v1 scope is plain text + a `completed` flag — no due dates, no
 * priority, no audio. That keeps the data model small and the voice
 * UX simple ("add buy milk to my tasks" → row in / "complete buy
 * milk" → row toggled). Future fields would land via a Room migration.
 *
 * Indexed by `completed` so the watch's "show my pending tasks" path
 * doesn't full-scan when the completed list grows large.
 */
@Entity(
    tableName = "tasks",
    indices = [Index(value = ["completed"])],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The task text, with the spoken lead-in already stripped (see AddTaskSlots). */
    val text: String,
    /** `false` until the user marks it done. */
    val completed: Boolean = false,
    /** Wall-clock when the task was created. Used to order the pending list (newest first). */
    val createdAtEpochMs: Long,
    /** Wall-clock when the task was marked complete. Null while pending. */
    val completedAtEpochMs: Long? = null,
    /**
     * Where the task came from — `"watch"` (voice dictation),
     * `"companion_voice"` (Phase C if we add it), `"companion_typed"`
     * (Phase C). Free-form string for diagnostics; the app doesn't
     * branch on the value today.
     */
    val source: String,
)
