// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class StatsRepositoryTest {

    // Wed 2026-06-11 10:00:00 UTC — the "now" anchor every test shares.
    private val now = 1781517600000L
    private val msPerDay = 24 * 60 * 60 * 1000L

    @Test fun emptySource_producesZeroValuedSnapshot() = runTest {
        val repo = StatsRepository(FakeStatsSource(), now = { now })

        val s = repo.snapshot()

        assertNull(s.firstUseEpochMs)
        assertEquals(0, s.lifetime.totalQueries)
        assertEquals(0, s.window30d.totalQueries)
        assertEquals(24, s.lifetime.hourBuckets.size)
        assertEquals(7, s.lifetime.dayOfWeekBuckets.size)
        assertEquals(0, s.lifetime.hourBuckets.sum())
        assertEquals(0, s.tally.notes)
        assertNull(s.lifetime.successRate)
    }

    @Test fun lifetimeAggregate_sumsTotalsAndSuccesses() = runTest {
        val source = FakeStatsSource(
            lifetimeTotal = 100,
            lifetimeSuccess = 92,
            lifetimeQueries = listOf("hello world", "set a reminder", "  call    mom  "),
            lifetimeHandlerStats = listOf(
                HandlerStats(handler = "reminder", total = 50, successful = 48),
                HandlerStats(handler = "call", total = 30, successful = 28),
                HandlerStats(handler = "unknown", total = 20, successful = 16),
            ),
            lifetimeHourCounts = listOf(HourBucketCount(8, 40), HourBucketCount(22, 60)),
            lifetimeDayCounts = listOf(DayBucketCount(2, 70), DayBucketCount(5, 30)),
            lifetimeAvgConfidence = 0.78f,
            firstTimestamp = now - 60 * msPerDay,
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        assertEquals(100, s.lifetime.totalQueries)
        assertEquals(92, s.lifetime.successfulQueries)
        // "hello world" (2) + "set a reminder" (3) + "call mom" (2) = 7
        assertEquals(7L, s.lifetime.wordsDictated)
        assertNotNull(s.lifetime.successRate)
        assertEquals(0.92f, s.lifetime.successRate!!, 0.0001f)
        assertEquals(0.78f, s.lifetime.avgNluConfidence!!, 0.0001f)
        assertEquals(now - 60 * msPerDay, s.firstUseEpochMs)
    }

    @Test fun handlerCounts_areSortedByCountDescending() = runTest {
        val source = FakeStatsSource(
            lifetimeHandlerStats = listOf(
                HandlerStats("call", total = 5, successful = 5),
                HandlerStats("reminder", total = 50, successful = 48),
                HandlerStats("note", total = 20, successful = 20),
            ),
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        assertEquals(
            listOf("reminder", "note", "call"),
            s.lifetime.handlerCounts.map { it.handler },
        )
        assertEquals(listOf(50, 20, 5), s.lifetime.handlerCounts.map { it.count })
    }

    @Test fun hourAndDayBuckets_areDenseAndZeroFilled() = runTest {
        val source = FakeStatsSource(
            lifetimeHourCounts = listOf(HourBucketCount(0, 1), HourBucketCount(23, 4)),
            lifetimeDayCounts = listOf(DayBucketCount(0, 7), DayBucketCount(6, 3)),
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        assertEquals(24, s.lifetime.hourBuckets.size)
        assertEquals(1, s.lifetime.hourBuckets[0])
        assertEquals(4, s.lifetime.hourBuckets[23])
        assertEquals(0, s.lifetime.hourBuckets[12])
        assertEquals(7, s.lifetime.dayOfWeekBuckets[0])
        assertEquals(3, s.lifetime.dayOfWeekBuckets[6])
        assertEquals(0, s.lifetime.dayOfWeekBuckets[3])
    }

    @Test fun thirtyDayWindow_isReportedIndependentlyOfLifetime() = runTest {
        val source = FakeStatsSource(
            lifetimeTotal = 500,
            lifetimeSuccess = 450,
            windowTotal = 60,
            windowSuccess = 58,
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        assertEquals(500, s.lifetime.totalQueries)
        assertEquals(60, s.window30d.totalQueries)
        // Both surface; the success-rate panel renders them side-by-side.
        assertEquals(0.9f, s.lifetime.successRate!!, 0.0001f)
        assertEquals(0.9666f, s.window30d.successRate!!, 0.001f)
    }

    @Test fun handlerSuccess_exposesTotalAndSuccessfulForHardestIntentPicker() = runTest {
        val source = FakeStatsSource(
            lifetimeHandlerStats = listOf(
                HandlerStats("calendar", total = 32, successful = 19),    // 0.59 → hardest
                HandlerStats("reminder", total = 50, successful = 48),    // 0.96
                HandlerStats("note", total = 20, successful = 19),        // 0.95
            ),
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        val calendar = s.lifetime.handlerSuccess.first { it.handler == "calendar" }
        assertEquals(32, calendar.total)
        assertEquals(19, calendar.successful)
        assertNotNull(calendar.rate)
        assertEquals(0.5938f, calendar.rate!!, 0.001f)
    }

    @Test fun personalisationAndTally_pullFromSourceDirectly() = runTest {
        val source = FakeStatsSource(
            learnedPhrases = 12,
            appAliases = 8,
            contactAliases = 5,
            notesCount = 47,
            tasksTotal = 140,
            tasksPending = 8,
            alarmsCount = 14,
            remindersCount = 89,
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        assertEquals(12, s.personalisation.learnedNluPhrases)
        assertEquals(8, s.personalisation.appAliases)
        assertEquals(5, s.personalisation.contactAliases)
        assertEquals(47, s.tally.notes)
        assertEquals(132, s.tally.tasksCompleted)
        assertEquals(8, s.tally.tasksPending)
        assertEquals(89, s.tally.reminders)
        assertEquals(14, s.tally.alarms)
    }

    @Test fun wordCounter_handlesBlankEmptyAndMultiSpaceInputs() = runTest {
        val source = FakeStatsSource(
            lifetimeQueries = listOf(
                "",
                "   ",
                "one",
                "two  words",
                " leading and trailing ",
                "tab\tseparated\twords",
            ),
        )
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        // 0 + 0 + 1 + 2 + 3 + 3 = 9
        assertEquals(9L, s.lifetime.wordsDictated)
    }

    @Test fun firstUseEpochMs_surfacesDirectly() = runTest {
        val firstUse = now - 41 * msPerDay
        val source = FakeStatsSource(firstTimestamp = firstUse)
        val repo = StatsRepository(source, now = { now })

        val s = repo.snapshot()

        assertNotNull(s.firstUseEpochMs)
        assertEquals(firstUse, s.firstUseEpochMs)
    }

    /**
     * Canned-response fake — the `since` arg only chooses between
     * "lifetime" and "window" buckets; timestamps don't actually drive
     * the response so tests can pin exact expected values without
     * worrying about clock or timezone.
     */
    private class FakeStatsSource(
        val lifetimeTotal: Int = 0,
        val lifetimeSuccess: Int = 0,
        val lifetimeQueries: List<String> = emptyList(),
        val lifetimeHandlerStats: List<HandlerStats> = emptyList(),
        val lifetimeHourCounts: List<HourBucketCount> = emptyList(),
        val lifetimeDayCounts: List<DayBucketCount> = emptyList(),
        val lifetimeAvgConfidence: Float? = null,
        val windowTotal: Int = 0,
        val windowSuccess: Int = 0,
        val windowQueries: List<String> = emptyList(),
        val windowHandlerStats: List<HandlerStats> = emptyList(),
        val windowHourCounts: List<HourBucketCount> = emptyList(),
        val windowDayCounts: List<DayBucketCount> = emptyList(),
        val windowAvgConfidence: Float? = null,
        val firstTimestamp: Long? = null,
        val notesCount: Int = 0,
        val tasksTotal: Int = 0,
        val tasksPending: Int = 0,
        val alarmsCount: Int = 0,
        val learnedPhrases: Int = 0,
        val appAliases: Int = 0,
        val contactAliases: Int = 0,
        val remindersCount: Int = 0,
    ) : StatsSource {

        private fun lifetime(since: Long): Boolean = since == 0L

        override suspend fun firstConversationTimestamp(): Long? = firstTimestamp
        override suspend fun countConversationsSince(since: Long): Int =
            if (lifetime(since)) lifetimeTotal else windowTotal
        override suspend fun countSuccessfulConversationsSince(since: Long): Int =
            if (lifetime(since)) lifetimeSuccess else windowSuccess
        override suspend fun userQueriesSince(since: Long): List<String> =
            if (lifetime(since)) lifetimeQueries else windowQueries
        override suspend fun handlerStatsSince(since: Long): List<HandlerStats> =
            if (lifetime(since)) lifetimeHandlerStats else windowHandlerStats
        override suspend fun hourBucketsSince(since: Long): List<HourBucketCount> =
            if (lifetime(since)) lifetimeHourCounts else windowHourCounts
        override suspend fun dayOfWeekBucketsSince(since: Long): List<DayBucketCount> =
            if (lifetime(since)) lifetimeDayCounts else windowDayCounts
        override suspend fun avgNluConfidenceSince(since: Long): Float? =
            if (lifetime(since)) lifetimeAvgConfidence else windowAvgConfidence
        override suspend fun notesCount(): Int = notesCount
        override suspend fun tasksTotalCount(): Int = tasksTotal
        override suspend fun tasksPendingCount(): Int = tasksPending
        override suspend fun alarmsCount(): Int = alarmsCount
        override suspend fun learnedNluPhrasesCount(): Int = learnedPhrases
        override suspend fun appAliasesCount(): Int = appAliases
        override suspend fun contactAliasesCount(): Int = contactAliases
        override suspend fun remindersCount(): Int = remindersCount
    }
}

