// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One captured note. Unlike conversation entries, notes are long-term
 * user data — kept until the user deletes them (or evicted by the
 * keep-last-N FIFO when the user sets a cap).
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The note text, with the spoken lead-in already stripped (see NoteSlots). */
    val body: String,
    /** Wall-clock when the note was created. Indexed by the DAO. */
    val createdAtEpochMs: Long,
    /**
     * Where the note came from. "watch" (microPebble dictation),
     * "companion_voice" (Phase B), "companion_typed" (Phase B).
     */
    val source: String,
    /**
     * Absolute path to the per-note `.wav` (microPebble dictations only).
     * Phase A copies the dictation's transient conversation-audio file into
     * a permanent notes-audio/<id>.wav so eviction by the conversation-audio
     * FIFO doesn't break inline playback on the Notes screen.
     */
    val audioFilePath: String? = null,
)