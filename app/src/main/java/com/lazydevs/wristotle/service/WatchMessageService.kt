package com.lazydevs.wristotle.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.transport.PebbleTransport
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the companion alive and announces itself to the watch on start.
 *
 * Incoming watch messages are handled by [PebbleListenerService], which is bound by
 * rePebble and dispatches queries through the handler registry.
 */
class WatchMessageService : LifecycleService() {

    private lateinit var transport: PebbleTransport

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AppConstants.Notifications.SERVICE_NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(AppConstants.Notifications.SERVICE_NOTIFICATION_ID, buildNotification())
        }

        transport = (application as WristotleApplication).transport
        lifecycleScope.launch {
            Log.d(TAG, "Sending COMPANION_READY on startup...")
            val result = transport.sendReady()
            Log.d(TAG, "sendReady result: $result")
        }
    }

    // Transport is Application-owned; no close in onDestroy.

    companion object {
        private const val TAG = "WatchMessageService"
    }

    private fun buildNotification() = NotificationCompat.Builder(this, AppConstants.Notifications.CHANNEL_ID)
        .setContentTitle(getString(R.string.service_notification_title))
        .setContentText(getString(R.string.service_notification_text))
        // Expanded body explains that dismissing the notification does not stop
        // the service — Android 13+ made foreground service notifications
        // user-dismissable, which is easy to misread as "service stopped".
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(getString(R.string.service_notification_big_text)),
        )
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.Notifications.CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
