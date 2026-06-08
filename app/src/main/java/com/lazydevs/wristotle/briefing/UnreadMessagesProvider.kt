// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.briefing

import android.service.notification.StatusBarNotification
import com.lazydevs.wristotle.media.MediaSessionsListener
import com.lazydevs.wristotle.notifications.NotificationFilter

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
class UnreadMessagesProvider {

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

        // Dedupe by a per-conversation key so "8 SMS notifications
        // across 2 conversations" counts as 2. AOSP Messaging (the
        // GrapheneOS / non-GMS default) posts a fresh notification per
        // incoming SMS within a conversation, and any other app with
        // a chatty notification style would otherwise inflate the
        // brief count the same way.
        val actionable = active
            .filter { it.isActionable() }
            .distinctBy { it.conversationKey() }

        val (messaging, other) = actionable.partition {
            MessagingApps.labelOf(it.packageName) != null
        }

        val sections = messaging
            .groupingBy { MessagingApps.labelOf(it.packageName)!! }
            .eachCount()
            .map { (label, count) -> Section(label, count) }
            .sortedWith(compareByDescending<Section> { it.count }.thenBy { it.label })

        return Snapshot(messaging = sections, otherCount = other.size)
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
}