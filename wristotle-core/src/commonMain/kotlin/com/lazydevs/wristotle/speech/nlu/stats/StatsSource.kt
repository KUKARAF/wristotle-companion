// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.stats

/**
 * Platform seam that [StatsRepository] reads from. The Android implementation
 * adapts the Room DAOs + prefs-backed alias stores + PinStore; an iOS port
 * would back the same interface with whatever persistence ships there.
 *
 * All conversation-aggregate methods accept a `since` epoch-ms cutoff
 * (inclusive). Pass `since = 0L` for lifetime.
 *
 * Result rows ([HandlerStats], [HourBucketCount], [DayBucketCount]) live
 * in this module so Android's Room can project directly into them and the
 * repository code stays platform-agnostic.
 */
interface StatsSource {

    // ── Conversation aggregates ─────────────────────────────────────

    /** Wall-clock of the very first conversation entry, or null if empty. */
    suspend fun firstConversationTimestamp(): Long?

    suspend fun countConversationsSince(since: Long): Int

    suspend fun countSuccessfulConversationsSince(since: Long): Int

    suspend fun avgNluConfidenceSince(since: Long): Float?

    suspend fun handlerStatsSince(since: Long): List<HandlerStats>

    suspend fun hourBucketsSince(since: Long): List<HourBucketCount>

    suspend fun dayOfWeekBucketsSince(since: Long): List<DayBucketCount>

    /** Raw user-query texts in window — the repository sums word counts in pure Kotlin. */
    suspend fun userQueriesSince(since: Long): List<String>

    // ── Domain lifetime counts ──────────────────────────────────────

    suspend fun notesCount(): Int
    suspend fun tasksTotalCount(): Int
    suspend fun tasksPendingCount(): Int
    suspend fun alarmsCount(): Int
    suspend fun learnedNluPhrasesCount(): Int
    suspend fun appAliasesCount(): Int
    suspend fun contactAliasesCount(): Int
    suspend fun remindersCount(): Int
}

/** Per-handler total + successful counts in window. */
data class HandlerStats(
    val handler: String,
    val total: Int,
    val successful: Int,
)

/** Bucket of conversations falling within an hour-of-day (0..23). Local time. */
data class HourBucketCount(
    val hour: Int,
    val count: Int,
)

/** Bucket of conversations falling within a day-of-week (0=Sunday..6=Saturday). Local time. */
data class DayBucketCount(
    val day: Int,
    val count: Int,
)
