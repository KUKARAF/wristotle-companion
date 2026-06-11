// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lazydevs.wristotle.speech.nlu.stats.DayBucketCount
import com.lazydevs.wristotle.speech.nlu.stats.HandlerStats
import com.lazydevs.wristotle.speech.nlu.stats.HourBucketCount
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

    // ── Stats aggregates ────────────────────────────────────────────
    //
    // All windowed queries take a `since` epoch-ms cutoff (inclusive).
    // Pass `since = 0L` for lifetime. Local-time hour/day extraction
    // uses SQLite's strftime so the bucketing reflects the user's
    // timezone, not UTC.

    /** First entry's timestamp — drives the "Day N" hook. Null when empty. */
    @Query("SELECT MIN(timestampEpochMs) FROM conversation_entries")
    suspend fun minTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM conversation_entries WHERE timestampEpochMs >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM conversation_entries WHERE timestampEpochMs >= :since AND success = 1")
    suspend fun countSuccessSince(since: Long): Int

    @Query("SELECT AVG(nluConfidence) FROM conversation_entries WHERE timestampEpochMs >= :since AND nluConfidence IS NOT NULL")
    suspend fun avgNluConfidenceSince(since: Long): Float?

    /** Per-handler totals + successes, for the "Top intents" + "Hardest intent" panels. */
    @Query(
        """
        SELECT handler,
               COUNT(*) AS total,
               SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successful
        FROM conversation_entries
        WHERE timestampEpochMs >= :since
        GROUP BY handler
        """,
    )
    suspend fun handlerStatsSince(since: Long): List<HandlerStats>

    /** Hour-of-day buckets (0..23) in the user's local time. Only buckets with rows appear. */
    @Query(
        """
        SELECT CAST(strftime('%H', timestampEpochMs / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS count
        FROM conversation_entries
        WHERE timestampEpochMs >= :since
        GROUP BY hour
        """,
    )
    suspend fun countByHourSince(since: Long): List<HourBucketCount>

    /** Day-of-week buckets (0=Sunday..6=Saturday) in the user's local time. */
    @Query(
        """
        SELECT CAST(strftime('%w', timestampEpochMs / 1000, 'unixepoch', 'localtime') AS INTEGER) AS day,
               COUNT(*) AS count
        FROM conversation_entries
        WHERE timestampEpochMs >= :since
        GROUP BY day
        """,
    )
    suspend fun countByDayOfWeekSince(since: Long): List<DayBucketCount>

    /** Raw user-query texts in window — stats sums their word counts in Kotlin.
     *  Bounded by retention (default 10 days), so even chatty users stay well
     *  under 10K rows. */
    @Query("SELECT userQuery FROM conversation_entries WHERE timestampEpochMs >= :since")
    suspend fun userQueriesSince(since: Long): List<String>
}