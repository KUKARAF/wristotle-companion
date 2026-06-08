// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import android.app.Notification

/**
 * Pure helpers for deciding "is this notification interesting to the
 * morning brief / persisted log?" and "what conversation does it belong
 * to?". Takes primitive args instead of `StatusBarNotification` so the
 * logic is unit-testable without Robolectric — both
 * [com.lazydevs.wristotle.briefing.UnreadMessagesProvider] (snapshot
 * path) and [com.lazydevs.wristotle.media.MediaSessionsListener]
 * (persisted-log path) route through the same rules.
 */
internal object NotificationFilter {

    private const val OUR_PACKAGE = "com.lazydevs.wristotle"

    /**
     * Strips notifications that aren't user-actionable so the brief stays
     * signal. Mirrors what `UnreadMessagesProvider.snapshot()` has always
     * applied — the rules live here now so the log doesn't grow a row for
     * every transient foreground-service ping.
     *
     *  - Wristotle's own package (defensively — already covered by
     *    `FLAG_FOREGROUND_SERVICE` for the WatchMessageService, but explicit
     *    so a future non-FGS notification we add can't inflate the count).
     *  - Group summaries (`FLAG_GROUP_SUMMARY`) — the leaf notifications
     *    are also in the active set, so counting both would double-count.
     *  - Ongoing events (`FLAG_ONGOING_EVENT`) — navigation, media handles,
     *    foreground-service indicators. Persistent by design.
     *  - Foreground-service notifications (`FLAG_FOREGROUND_SERVICE`).
     *  - No-clear notifications (`FLAG_NO_CLEAR`) — system / OS-pinned.
     */
    fun isActionable(packageName: String, flags: Int): Boolean {
        if (packageName == OUR_PACKAGE) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (flags and Notification.FLAG_NO_CLEAR != 0) return false
        return true
    }

    /**
     * Stable identity for "the conversation this notification belongs to"
     * within a single app. AOSP Messaging posts one fresh notification per
     * incoming SMS within a conversation; without a stable key, four SMS
     * messages across two conversations would count as four. Falls back
     * through the chain so apps that don't model conversations still
     * group cleanly.
     *
     *  1. `shortcutId` — modern conversation API.
     *  2. `channelId` — AOSP Messaging encodes the thread id here.
     *  3. `tag` — some apps stash the conversation id in the tag.
     *  4. `id` — last resort; the (tag, id) pair is unique per notification
     *     slot so this still groups cleanly for the apps that don't use
     *     the other hooks.
     *
     * Never reads body / title / extras — only the routing metadata.
     */
    fun conversationKey(
        packageName: String,
        shortcutId: String?,
        channelId: String?,
        tag: String?,
        id: Int,
    ): String {
        shortcutId?.takeIf { it.isNotBlank() }?.let {
            return "$packageName/sc/$it"
        }
        channelId?.takeIf { it.isNotBlank() }?.let {
            return "$packageName/ch/$it"
        }
        tag?.takeIf { it.isNotBlank() }?.let {
            return "$packageName/tag/$it"
        }
        return "$packageName/id/$id"
    }
}
