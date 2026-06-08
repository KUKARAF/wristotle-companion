// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.briefing

import android.service.notification.StatusBarNotification
import com.lazydevs.wristotle.media.MediaSessionsListener
import com.lazydevs.wristotle.notifications.NotificationFilter
import com.lazydevs.wristotle.notifications.NotificationPostDao

/**
 * Adapter over [MediaSessionsListener]'s snapshotActiveNotifications.
 * Buckets active notifications into named messaging-app sections
 * (via [MessagingApps]) plus an `otherCount` for everything else
 * that's still actionable.
 *
 * Filters applied — these strip notifications that aren't user-
 * actionable so the brief stays signal:
 *
 *  - Group summaries (counting both the parent + its leaves would
 *    double-count a single conversation).
 *  - Ongoing notifications (`FLAG_ONGOING_EVENT`) — foreground-
 *    service indicators, media-playback handles, navigation, etc.
 *    Persistent by design; not "1 thing waiting for you".
 *  - Foreground-service notifications (`FLAG_FOREGROUND_SERVICE`) —
 *    same rationale; specifically includes Wristotle's own
 *    WatchMessageService notification.
 *  - No-clear notifications (`FLAG_NO_CLEAR`) — system / OS-pinned
 *    items the user can't dismiss anyway.
 *  - Wristotle's own package, defensively (already covered by FGS
 *    flag, but explicit so a future non-FGS notification we add
 *    doesn't accidentally inflate the count).
 *
 * Snapshot semantics — no persistence. A notification the user has
 * already dismissed never appears in the brief.
 */
class UnreadMessagesProvider(
    /** Phase B+: read from the persisted post log when the toggle is on
     *  ([todayPosts]). Nullable so the legacy snapshot-only path stays
     *  callable without wiring the DAO. */
    private val postsDao: NotificationPostDao? = null,
) {

    /**
     * Per-app messaging count. `conversations` is dedup'd by
     * `conversationKey` so two SMS from the same friend count as 1
     * conversation; `messages` is the raw count (no dedupe) so the brief
     * can also surface volume (`"2 conversations (17 msgs)"`) when
     * they diverge.
     */
    data class Section(val label: String, val conversations: Int, val messages: Int)

    data class Snapshot(
        /** Messaging-app counts, ordered conversations-descending then label-ascending. */
        val messaging: List<Section>,
        /** Notifications that didn't match any messaging token; dedup'd
         *  by conversation key (the user can't act on these so the raw
         *  count isn't useful here). */
        val otherCount: Int,
    ) {
        val totalConversations: Int get() = messaging.sumOf { it.conversations } + otherCount
        val totalMessages: Int get() = messaging.sumOf { it.messages } + otherCount
        val isEmpty: Boolean get() = messaging.isEmpty() && otherCount == 0
    }

    /** Empty Snapshot when notification access isn't granted, the
     *  listener service isn't bound, or every active notification
     *  was filtered out. */
    fun snapshot(): Snapshot = aggregate(collectSnapshotRows())

    /**
     * Phase B query path — reads posts in the half-open [today.startMs,
     * nowMs] range from the persisted log and buckets them the same way
     * [snapshot] buckets active notifications. Returns an empty Snapshot
     * when no DAO was injected.
     *
     * `nowMs + 1` is used as the upper bound so a notification posted
     * during this exact millisecond still lands in the result.
     */
    suspend fun todayPosts(nowMs: Long = System.currentTimeMillis()): Snapshot {
        val rows = collectTodayPostRows(nowMs) ?: return Snapshot(emptyList(), 0)
        return aggregate(rows)
    }

    /**
     * Union of the snapshot rows + today's persisted-log rows,
     * deduped by conversation key and aggregated by [aggregate]. This is
     * the path the handler runs when the user has opted into the log:
     *
     *  - Notifications currently sitting in the tray (snapshot rows)
     *    AND notifications posted earlier today that the user has since
     *    dismissed (log rows) both land in the count.
     *  - Same conversation appearing in both sets is counted once.
     *
     * Solves the "I just turned the log on, my brief shrunk" case:
     * snapshot's tray contents stay in the answer until the log catches
     * up tomorrow.
     */
    suspend fun snapshotPlusTodayPosts(nowMs: Long = System.currentTimeMillis()): Snapshot {
        val snapshotRows = collectSnapshotRows()
        val logRows = collectTodayPostRows(nowMs) ?: emptyList()
        return aggregate(snapshotRows + logRows)
    }

    /** Pure shape-conversion from active notifications → Rows. Empty if
     *  the listener isn't bound; the [Row] list is the natural empty
     *  return rather than null so callers can `+` it freely. */
    private fun collectSnapshotRows(): List<Row> {
        val active = MediaSessionsListener.snapshotActiveNotifications()
            ?: return emptyList()
        return active.mapNotNull { sbn ->
            if (!sbn.isActionable()) return@mapNotNull null
            Row(sbn.packageName, sbn.conversationKey())
        }
    }

    /** DAO read + map. Null when no DAO was injected (signals to callers
     *  that the log path isn't wired, so they can pick the snapshot-only
     *  fallback). */
    private suspend fun collectTodayPostRows(nowMs: Long): List<Row>? {
        val dao = postsDao ?: return null
        val today = TodayRange.now(nowMs)
        return dao
            .postsBetween(fromEpochMs = today.startMs, untilEpochMs = nowMs + 1)
            .map { Row(it.packageName, it.conversationKey) }
    }

    /** Routes through the pure [NotificationFilter] so the listener
     *  side (persisted-log path) sees the exact same definition of
     *  "conversation." */
    private fun StatusBarNotification.conversationKey(): String =
        NotificationFilter.conversationKey(
            packageName = packageName,
            shortcutId = notification?.shortcutId,
            channelId = notification?.channelId,
            tag = tag,
            id = id,
        )

    /** Routes through [NotificationFilter] for the same reason. */
    private fun StatusBarNotification.isActionable(): Boolean =
        NotificationFilter.isActionable(packageName, notification?.flags ?: 0)

    /** One unit of "something to count": a package id + its
     *  conversation key. Both the snapshot and posts-log paths
     *  produce a list of these and hand it to [aggregate]. */
    internal data class Row(val packageName: String, val conversationKey: String)

    companion object {

        /**
         * Pure: partition by whether the package matches a [MessagingApps]
         * token; for each matched app, count distinct conversations AND
         * total messages (no dedupe). Sort sections conversations-desc
         * then label-asc. Unmatched ("other") notifications are dedup'd
         * by conversation key — the raw count doesn't help the user.
         * No I/O, no Android imports — fully unit-testable.
         */
        internal fun aggregate(rows: List<Row>): Snapshot {
            val (messaging, other) = rows.partition {
                MessagingApps.labelOf(it.packageName) != null
            }
            val sections = messaging
                .groupBy { MessagingApps.labelOf(it.packageName)!! }
                .map { (label, group) ->
                    Section(
                        label = label,
                        conversations = group.distinctBy { it.conversationKey }.size,
                        messages = group.size,
                    )
                }
                .sortedWith(
                    compareByDescending<Section> { it.conversations }.thenBy { it.label },
                )
            val otherDedup = other.distinctBy { it.conversationKey }.size
            return Snapshot(messaging = sections, otherCount = otherDedup)
        }
    }
}