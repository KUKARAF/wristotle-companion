// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

private const val TAG = "MediaSessionsListener"

/**
 * [NotificationListenerService] hook the rest of the app uses.
 *
 * Original purpose (still primary): Android grants
 * `MediaSessionManager.getActiveSessions()` to processes that host a
 * NotificationListenerService AND that have been granted Notification
 * Access in system Settings. That permission is the load-bearing bit —
 * onNotificationPosted / onNotificationRemoved remain no-ops because
 * media-control doesn't need to inspect notification content.
 *
 * Added with the Morning Brief unread-messages section: we expose the
 * service's `activeNotifications` array via a static snapshot getter
 * so the brief can count messaging-app notifications without binding
 * a separate listener (each one would need its own Notification
 * Access grant in system Settings — bad UX). The snapshot is a
 * point-in-time read of currently-posted notifications; we don't
 * persist anything, so a notification the user has already dismissed
 * never appears in the brief.
 *
 * The user grants the permission via system Settings (intent
 * `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`); the system then
 * binds this service whenever notification events fire.
 *
 * Companion-app code reaches the active media sessions through
 * [ActiveMediaSession], passing this service's [ComponentName] to
 * `getActiveSessions(notificationListener = ...)`.
 */
class MediaSessionsListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "notification listener connected — MediaSessionManager.getActiveSessions() is now available")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, "notification listener disconnected")
    }

    // Deliberate no-ops: we never inspect or persist notifications on
    // posted / removed callbacks. The Morning Brief reads
    // `activeNotifications` on demand instead.
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        // Updated by the lifecycle callbacks. @Volatile so a reader
        // on another thread sees the latest value without a fence.
        @Volatile private var instance: MediaSessionsListener? = null

        /** ComponentName the [android.media.session.MediaSessionManager] APIs need. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaSessionsListener::class.java)

        /**
         * Snapshot of currently-posted notifications. Returns null when
         * the listener service isn't bound (Notification Access not
         * granted, or the system hasn't bound the service yet).
         *
         * Calls through to the inherited
         * [NotificationListenerService.getActiveNotifications], which
         * is documented as a live read from the system — no caching on
         * our side. Cheap; safe to call from any thread.
         */
        fun snapshotActiveNotifications(): Array<StatusBarNotification>? =
            try {
                instance?.activeNotifications
            } catch (t: SecurityException) {
                // Defensive: getActiveNotifications throws SecurityException
                // if called while the service isn't bound. The instance
                // null-check above should cover this, but the JVM's
                // happens-before guarantees with @Volatile don't extend
                // into the binder transaction itself.
                Log.w(TAG, "active-notifications snapshot threw — returning null", t)
                null
            }
    }
}