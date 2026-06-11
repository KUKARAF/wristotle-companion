// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.stats

import com.lazydevs.wristotle.alarms.AlarmDao
import com.lazydevs.wristotle.history.ConversationDao
import com.lazydevs.wristotle.nlu.learning.ExampleDao
import com.lazydevs.wristotle.notes.NoteDao
import com.lazydevs.wristotle.speech.nlu.stats.DayBucketCount
import com.lazydevs.wristotle.speech.nlu.stats.HandlerStats
import com.lazydevs.wristotle.speech.nlu.stats.HourBucketCount
import com.lazydevs.wristotle.speech.nlu.stats.StatsSource
import com.lazydevs.wristotle.tasks.TaskDao

/**
 * Android [StatsSource] — thin adapter wiring the platform's Room DAOs,
 * Room-backed example bank, prefs-backed alias stores, and the PinStore
 * (SharedPreferences) into the shape the pure-Kotlin
 * [com.lazydevs.wristotle.speech.nlu.stats.StatsRepository] consumes.
 *
 * The Room-projection result classes ([HandlerStats], [HourBucketCount],
 * [DayBucketCount]) come from `:wristotle-core` so this layer doesn't
 * need to translate at the boundary — the DAOs already return the core
 * shape directly.
 *
 * The three non-DAO counts are lambdas (vs. concrete deps) so the
 * application wiring can supply `AliasStore.all().size`,
 * `ContactAliasStore.all().size`, and `PinStore.all().size` without
 * forcing the adapter to know about those types.
 */
class RoomStatsSource(
    private val conversationDao: ConversationDao,
    private val noteDao: NoteDao,
    private val taskDao: TaskDao,
    private val alarmDao: AlarmDao,
    private val exampleDao: ExampleDao,
    private val appAliasCount: suspend () -> Int,
    private val contactAliasCount: suspend () -> Int,
    private val reminderCount: suspend () -> Int,
) : StatsSource {

    override suspend fun firstConversationTimestamp(): Long? =
        conversationDao.minTimestamp()

    override suspend fun countConversationsSince(since: Long): Int =
        conversationDao.countSince(since)

    override suspend fun countSuccessfulConversationsSince(since: Long): Int =
        conversationDao.countSuccessSince(since)

    override suspend fun avgNluConfidenceSince(since: Long): Float? =
        conversationDao.avgNluConfidenceSince(since)

    override suspend fun handlerStatsSince(since: Long): List<HandlerStats> =
        conversationDao.handlerStatsSince(since)

    override suspend fun hourBucketsSince(since: Long): List<HourBucketCount> =
        conversationDao.countByHourSince(since)

    override suspend fun dayOfWeekBucketsSince(since: Long): List<DayBucketCount> =
        conversationDao.countByDayOfWeekSince(since)

    override suspend fun userQueriesSince(since: Long): List<String> =
        conversationDao.userQueriesSince(since)

    override suspend fun notesCount(): Int = noteDao.count()

    override suspend fun tasksTotalCount(): Int = taskDao.count()

    override suspend fun tasksPendingCount(): Int = taskDao.countPending()

    override suspend fun alarmsCount(): Int = alarmDao.count()

    override suspend fun learnedNluPhrasesCount(): Int = exampleDao.countLearned()

    override suspend fun appAliasesCount(): Int = appAliasCount()

    override suspend fun contactAliasesCount(): Int = contactAliasCount()

    override suspend fun remindersCount(): Int = reminderCount()
}
