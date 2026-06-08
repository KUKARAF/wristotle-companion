// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded "notification was posted on this device" event. Metadata
 * only — never carries the notification's body / title / extras.
 *
 *  - [packageName] is the source app's package id, used to match against
 *    the messaging-app substring registry in
 *    [com.lazydevs.wristotle.briefing.MessagingApps].
 *  - [conversationKey] dedupes "8 SMS messages across 2 conversations"
 *    down to 2 rows per brief. Computed by
 *    [NotificationFilter.conversationKey] at insert time.
 *  - [postedAtEpochMs] is wall-clock at the moment the OS told us about
 *    the notification. The brief query filters on this — `>= today.startMs`
 *    is "posted today."
 */
@Entity(
    tableName = "notification_posts",
    indices = [
        Index(value = ["postedAtEpochMs"]),
    ],
)
data class NotificationPost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val conversationKey: String,
    val postedAtEpochMs: Long,
)
