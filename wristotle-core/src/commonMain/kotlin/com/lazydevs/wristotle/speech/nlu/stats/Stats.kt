// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.stats

/**
 * Snapshot of the Wristotle activity stats surfaced on Settings → 📊 Stats.
 *
 * All numbers are computed on-device from the existing local stores;
 * nothing is persisted that wasn't already. Snapshots are produced on
 * demand by [StatsRepository.snapshot] and cached by the Stats
 * screen's ViewModel — recomputing is cheap (aggregates over bounded
 * conversation history).
 *
 * Lifetime + 30-day windows are both populated unconditionally so the
 * UI can render them side-by-side without re-querying. If more windows
 * are added later, prefer additional [Aggregate] fields over a
 * configurable window param so the per-panel copy stays stable.
 *
 * Lives in `:wristotle-core` so an eventual iOS port reuses the same
 * shape — the iOS adapter only needs to implement [StatsSource].
 */
data class Stats(
    /** Wall-clock of the very first conversation entry. Null on a fresh install. */
    val firstUseEpochMs: Long?,
    /** Lifetime aggregate — everything still on disk after retention pruning. */
    val lifetime: Aggregate,
    /** Last 30 days. Drives the heatmap + top-intents panels. */
    val window30d: Aggregate,
    val personalisation: Personalisation,
    val tally: LifetimeTally,
) {

    /**
     * Aggregate numbers over a time window. Use [lifetime] or [window30d]
     * accessor on [Stats] rather than instantiating directly.
     *
     * Hour and day-of-week buckets are dense arrays (zero-filled) so the
     * UI heatmap can index by position without null-checks.
     */
    data class Aggregate(
        val totalQueries: Int,
        val successfulQueries: Int,
        /** Sum of whitespace-split tokens across all queries in window. */
        val wordsDictated: Long,
        /** Per-handler total query counts, sorted by count desc. */
        val handlerCounts: List<HandlerCount>,
        /** Dense 24-slot list, index = local-time hour (0..23). */
        val hourBuckets: List<Int>,
        /** Dense 7-slot list, 0=Sunday..6=Saturday in local time. */
        val dayOfWeekBuckets: List<Int>,
        /** Mean cosine confidence across queries where the NLU classifier produced one. */
        val avgNluConfidence: Float?,
        /** Per-handler success / total — the UI picks the lowest-rate handler with
         *  enough samples for the "Hardest intent" hint. */
        val handlerSuccess: List<HandlerSuccess>,
    ) {
        val successRate: Float?
            get() = if (totalQueries == 0) null else successfulQueries.toFloat() / totalQueries
    }

    data class HandlerCount(val handler: String, val count: Int)

    data class HandlerSuccess(val handler: String, val total: Int, val successful: Int) {
        val rate: Float?
            get() = if (total == 0) null else successful.toFloat() / total
    }

    data class Personalisation(
        val learnedNluPhrases: Int,
        val appAliases: Int,
        val contactAliases: Int,
    )

    data class LifetimeTally(
        val notes: Int,
        val tasksCompleted: Int,
        val tasksPending: Int,
        val reminders: Int,
        val alarms: Int,
    )

    companion object {
        val EMPTY: Stats = Stats(
            firstUseEpochMs = null,
            lifetime = emptyAggregate(),
            window30d = emptyAggregate(),
            personalisation = Personalisation(0, 0, 0),
            tally = LifetimeTally(0, 0, 0, 0, 0),
        )

        private fun emptyAggregate(): Aggregate = Aggregate(
            totalQueries = 0,
            successfulQueries = 0,
            wordsDictated = 0L,
            handlerCounts = emptyList(),
            hourBuckets = List(24) { 0 },
            dayOfWeekBuckets = List(7) { 0 },
            avgNluConfidence = null,
            handlerSuccess = emptyList(),
        )
    }
}
