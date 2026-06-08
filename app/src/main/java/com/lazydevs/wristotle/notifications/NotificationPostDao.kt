// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationPostDao {
    @Insert
    suspend fun insert(post: NotificationPost): Long

    /**
     * All posts in [fromEpochMs, untilEpochMs), ordered newest-first.
     * The brief's "today" range gets passed in directly so the query
     * stays index-friendly (`postedAtEpochMs` is indexed).
     */
    @Query(
        "SELECT * FROM notification_posts " +
            "WHERE postedAtEpochMs >= :fromEpochMs AND postedAtEpochMs < :untilEpochMs " +
            "ORDER BY postedAtEpochMs DESC",
    )
    suspend fun postsBetween(fromEpochMs: Long, untilEpochMs: Long): List<NotificationPost>

    /** Phase E retention — drop rows older than [cutoffEpochMs]. */
    @Query("DELETE FROM notification_posts WHERE postedAtEpochMs < :cutoffEpochMs")
    suspend fun pruneOlderThan(cutoffEpochMs: Long): Int

    /** Phase C "Clear log" button + privacy-fail-safe reset. */
    @Query("DELETE FROM notification_posts")
    suspend fun deleteAll(): Int

    /** Count, used by the Settings card to show "N posts logged today."
     *  Flow so the card refreshes live as new posts land. */
    @Query("SELECT COUNT(*) FROM notification_posts WHERE postedAtEpochMs >= :fromEpochMs")
    fun observeCountSince(fromEpochMs: Long): Flow<Int>
}
