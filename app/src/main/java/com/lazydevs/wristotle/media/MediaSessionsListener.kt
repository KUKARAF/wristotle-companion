package com.lazydevs.wristotle.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

private const val TAG = "MediaSessionsListener"

/**
 * Empty [NotificationListenerService] whose only job is to exist.
 *
 * Android grants `MediaSessionManager.getActiveSessions()` to processes
 * that host a NotificationListenerService AND that have been granted
 * Notification Access in system Settings. We never look at notifications
 * ourselves — onNotificationPosted / onNotificationRemoved are no-ops.
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
        Log.d(TAG, "notification listener connected — MediaSessionManager.getActiveSessions() is now available")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "notification listener disconnected")
    }

    // Deliberate no-ops: we never inspect notifications.
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        /** ComponentName the [android.media.session.MediaSessionManager] APIs need. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaSessionsListener::class.java)
    }
}
