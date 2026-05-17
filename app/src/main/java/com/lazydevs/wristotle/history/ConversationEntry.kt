package com.lazydevs.wristotle.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per dictation/command exchange between the watch and companion.
 *
 * Stored locally on the phone; the watch only keeps the last 5 in RAM for
 * at-a-glance review. Retention is enforced by [ConversationRepository] —
 * entries older than 30 days are pruned on app start and after every insert.
 *
 * Fields that aren't always available (timings on local-only commands, etc.)
 * are nullable so we can persist a row immediately and still capture
 * everything we have.
 */
@Entity(tableName = "conversation_entries")
data class ConversationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock when the response was produced. Indexed via [ConversationDao]. */
    val timestampEpochMs: Long,
    /** Transcribed user voice input. */
    val userQuery: String,
    /** Response shown back on the watch chat UI. */
    val responseText: String,
    /**
     * Short tag identifying which handler produced the response:
     * "call", "sms", "reminder", "cancel", "find_phone", "time",
     * "battery", "vibrate", "steps", "unknown", "error".
     */
    val handler: String,
    /** True if the request was dispatched to the companion; false for watch-only locals. */
    val requiresCompanion: Boolean,
    /** True if the action completed normally; false for "Contact not found", "Couldn't understand the time", etc. */
    val success: Boolean,
    /** Speech duration in milliseconds, if known. Null for local commands that don't track it. */
    val audioDurationMs: Long? = null,
    /** Whisper inference wall-clock in milliseconds, if known. */
    val inferenceDurationMs: Long? = null,
    /** Encoder attention window used (256/512/768/1024/1500), if known. */
    val audioCtx: Int? = null,
    /** Whisper confidence score in [0.0, 1.0], if reported. */
    val confidence: Float? = null,
)
