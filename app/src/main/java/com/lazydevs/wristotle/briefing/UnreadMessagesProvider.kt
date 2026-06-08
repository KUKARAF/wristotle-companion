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

    data class Section(val label: String, val count: Int)

    data class Snapshot(
        /** Messaging-app counts, ordered count-descending then label-ascending. */
        val messaging: List<Section>,
        /** Notifications that didn't match any messaging token. */
        val otherCount: Int,
    ) {
        val totalCount: Int get() = messaging.sumOf { it.count } + otherCount
        val isEmpty: Boolean get() = messaging.isEmpty() && otherCount == 0
    }

    /** Empty Snapshot when notification access isn't granted, the
     *  listener service isn't bound, or every active notification
     *  was filtered out. */
    fun snapshot(): Snapshot {
        val active = MediaSessionsListener.snapshotActiveNotifications()
            ?: return Snapshot(emptyList(), 0)

        val rows = active.mapNotNull { sbn ->
            if (!sbn.isActionable()) return@mapNotNull null
            Row(sbn.packageName, sbn.conversationKey())
        }
        return aggregate(rows)
    }

    /**
     * Phase B query path — reads posts in the half-open [today.startMs,
     * nowMs] range from the persisted log and buckets them the same way
     * [snapshot] buckets active notifications. Returns an empty Snapshot
     * when no DAO was injected (caller forgot to wire it) so callers can
     * fall back to [snapshot] cleanly.
     *
     * `nowMs + 1` is used as the upper bound so a notification posted
     * during this exact millisecond still lands in the result.
     */
    suspend fun todayPosts(nowMs: Long = System.currentTimeMillis()): Snapshot {
        val dao = postsDao ?: return Snapshot(emptyList(), 0)
        val today = TodayRange.now(nowMs)
        val posts = dao.postsBetween(
            fromEpochMs = today.startMs,
            untilEpochMs = nowMs + 1,
        )
        val rows = posts.map { Row(it.packageName, it.conversationKey) }
        return aggregate(rows)
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
         * Pure: dedupe by conversationKey, partition by whether the
         * package matches a [MessagingApps] token, group the matched
         * side by label (count-desc then label-asc), and return the
         * matching [Snapshot]. No I/O, no Android imports — fully
         * unit-testable. Exposed `internal` so the matching test class
         * can exercise it directly.
         */
        internal fun aggregate(rows: List<Row>): Snapshot {
            val deduped = rows.distinctBy { it.conversationKey }
            val (messaging, other) = deduped.partition {
                MessagingApps.labelOf(it.packageName) != null
            }
            val sections = messaging
                .groupingBy { MessagingApps.labelOf(it.packageName)!! }
                .eachCount()
                .map { (label, count) -> Section(label, count) }
                .sortedWith(
                    compareByDescending<Section> { it.count }.thenBy { it.label },
                )
            return Snapshot(messaging = sections, otherCount = other.size)
        }
    }
}