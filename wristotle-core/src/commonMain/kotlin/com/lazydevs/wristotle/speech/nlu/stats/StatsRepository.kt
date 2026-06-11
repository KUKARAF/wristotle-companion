// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.stats

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Orchestrator for [Stats] snapshots — calls each [StatsSource] method in
 * parallel, normalises the results, and returns one immutable [Stats]
 * value the UI can render directly.
 *
 * No persistence of its own — the [StatsSource] adapter is the single
 * source of truth. Re-call [snapshot] on screen focus; the conversation
 * window stays small (retention prunes to ~10 days), so a full recompute
 * is cheap.
 */
class StatsRepository(
    private val source: StatsSource,
    private val now: () -> Long,
) {

    suspend fun snapshot(): Stats = coroutineScope {
        val nowMs = now()
        val since30d = nowMs - WINDOW_30D_MS

        val firstUseDef = async { source.firstConversationTimestamp() }
        val lifetimeDef = async { buildAggregate(since = 0L) }
        val windowDef = async { buildAggregate(since = since30d) }
        val personalisationDef = async {
            Stats.Personalisation(
                learnedNluPhrases = source.learnedNluPhrasesCount(),
                appAliases = source.appAliasesCount(),
                contactAliases = source.contactAliasesCount(),
            )
        }
        val tallyDef = async {
            val totalTasks = source.tasksTotalCount()
            val pendingTasks = source.tasksPendingCount()
            Stats.LifetimeTally(
                notes = source.notesCount(),
                tasksCompleted = totalTasks - pendingTasks,
                tasksPending = pendingTasks,
                reminders = source.remindersCount(),
                alarms = source.alarmsCount(),
            )
        }

        Stats(
            firstUseEpochMs = firstUseDef.await(),
            lifetime = lifetimeDef.await(),
            window30d = windowDef.await(),
            personalisation = personalisationDef.await(),
            tally = tallyDef.await(),
        )
    }

    private suspend fun buildAggregate(since: Long): Stats.Aggregate = coroutineScope {
        val totalDef = async { source.countConversationsSince(since) }
        val successDef = async { source.countSuccessfulConversationsSince(since) }
        val queriesDef = async { source.userQueriesSince(since) }
        val handlerStatsDef = async { source.handlerStatsSince(since) }
        val hourDef = async { source.hourBucketsSince(since) }
        val dayDef = async { source.dayOfWeekBucketsSince(since) }
        val avgConfDef = async { source.avgNluConfidenceSince(since) }

        val handlerStats = handlerStatsDef.await()
        Stats.Aggregate(
            totalQueries = totalDef.await(),
            successfulQueries = successDef.await(),
            wordsDictated = queriesDef.await().sumOf { wordCount(it).toLong() },
            handlerCounts = handlerStats
                .map { Stats.HandlerCount(it.handler, it.total) }
                .sortedByDescending { it.count },
            hourBuckets = densify(hourDef.await(), HOUR_BUCKETS) { it.hour to it.count },
            dayOfWeekBuckets = densify(dayDef.await(), DAY_BUCKETS) { it.day to it.count },
            avgNluConfidence = avgConfDef.await(),
            handlerSuccess = handlerStats.map {
                Stats.HandlerSuccess(it.handler, it.total, it.successful)
            },
        )
    }

    private inline fun <T> densify(
        rows: List<T>,
        size: Int,
        keyValue: (T) -> Pair<Int, Int>,
    ): List<Int> {
        val map = rows.associate(keyValue)
        return List(size) { map[it] ?: 0 }
    }

    private fun wordCount(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    private companion object {
        const val WINDOW_30D_MS: Long = 30L * 24 * 60 * 60 * 1000
        const val HOUR_BUCKETS = 24
        const val DAY_BUCKETS = 7
    }
}
