// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(entry: ConversationEntry): Long

    /** Newest first — what the Conversation screen renders. Flow so the UI updates live. */
    @Query("SELECT * FROM conversation_entries ORDER BY timestampEpochMs DESC")
    fun observeAllNewestFirst(): Flow<List<ConversationEntry>>

    /** Hard-delete anything older than [cutoffEpochMs]. Called on app start + after every insert. */
    @Query("DELETE FROM conversation_entries WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long): Int

    /** How many entries would be removed by a [pruneOlderThan] at the same cutoff.
     *  Used to preview the impact before shrinking the retention window. */
    @Query("SELECT COUNT(*) FROM conversation_entries WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun countOlderThan(cutoffEpochMs: Long): Int

    @Query("DELETE FROM conversation_entries")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversation_entries")
    suspend fun count(): Int

    /** One-shot snapshot of the [limit] most recent entries. Used by
     *  the diagnostics exporter, which wants a bounded list without
     *  collecting the full observeAll Flow. */
    @Query("SELECT * FROM conversation_entries ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ConversationEntry>

    /** Full snapshot of every entry for backup export. Ordered by id so the
     *  resulting JSON is deterministic across exports (round-trip tests). */
    @Query("SELECT * FROM conversation_entries ORDER BY id")
    suspend fun allForBackup(): List<ConversationEntry>
}