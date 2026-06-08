// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.notifier

/**
 * Platform-agnostic surface for posting + cancelling user-facing
 * notifications. The Android impl wraps `NotificationManager` +
 * `NotificationCompat.Builder`; an iOS impl would wrap
 * `UNUserNotificationCenter`.
 *
 * Action routing (what happens when the user taps an action button) is
 * NOT abstracted here — that's platform-deep (PendingIntent on Android,
 * UNNotificationAction.handler on iOS). Callers are expected to wire
 * their own routing in the platform layer; this interface only carries
 * the data the notification should display.
 *
 * R4 batch 2 — the formal seam for iOS port. Today only one impl exists
 * (Android), and the persistent-reminder receiver continues to construct
 * its own NotificationCompat.Builder directly because it needs custom
 * action-button PendingIntents the abstract interface can't carry.
 * Use this surface for new notification flows where a generic
 * title/body/cancel-by-id is sufficient.
 */
interface Notifier {
    /**
     * Post a user-facing notification immediately. If a notification with
     * the same [request].`id` is already showing, replace it.
     *
     * Returns true if the notification was posted; false if the platform
     * suppressed it (permission denied, channel disabled, etc.).
     */
    fun post(request: NotificationRequest): Boolean

    /** Cancel a previously-posted notification by ID. No-op if absent. */
    fun cancel(id: String)
}

/**
 * Pure-data description of a notification. The [id] doubles as the
 * platform notification ID — the Android impl hashes it into the int
 * NotificationManager wants; the iOS impl uses it verbatim as the
 * UNNotificationRequest identifier.
 */
data class NotificationRequest(
    val id: String,
    val title: String,
    val body: String,
)
