package com.lazydevs.wristotle.alarms

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted alarm row.
 *
 * Time-of-day only — recurring (day-of-week mask) is a deliberate v2-MVP
 * non-goal. Each alarm fires once per scheduling; re-scheduling for a
 * future day is handled at dispatch time by [AlarmDispatcher].
 *
 *  - [hour] / [minute] — wall-clock target.
 *  - [destination] — [AlarmDestination.name] String so adding a new
 *    enum value at the end doesn't break old databases.
 *  - [wireEpoch] — epoch SECONDS the watch is currently scheduled for;
 *    null when the watch leg isn't active (destination = Phone, or
 *    a previously-set watch alarm was cancelled or has fired).
 *    Phone-leg alarms have NO equivalent — we can't query the phone's
 *    clock app to confirm a schedule. The row's [destination] is the
 *    user's *intent*; the phone may or may not still hold it.
 *  - [enabled] — toggle for the list-row switch. Disabling cancels the
 *    watch leg (if any); re-enabling re-fires both legs.
 *  - [createdAtEpochMs] — debug breadcrumb.
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    val destination: String,
    val wireEpoch: Long?,
    val enabled: Boolean,
    val createdAtEpochMs: Long,
)
