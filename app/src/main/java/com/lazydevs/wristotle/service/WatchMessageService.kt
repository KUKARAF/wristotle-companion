package com.lazydevs.wristotle.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.handlers.CallHandler
import com.lazydevs.wristotle.handlers.HandlerRegistry
import com.lazydevs.wristotle.handlers.SmsHandler
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.transport.MessageKeys
import com.lazydevs.wristotle.transport.PebbleTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Foreground service that bridges the Pebble watch and Android phone capabilities.
 *
 * On startup it announces itself to the watch (companion_ready).
 * It then listens for companion_query messages and dispatches them through
 * the [HandlerRegistry]. To add a new capability, implement [ActionHandler][com.lazydevs.wristotle.handlers.ActionHandler]
 * and add it to the registry list in [onCreate] — nothing else needs to change.
 *
 * Uses [LifecycleService] so that handlers can launch coroutines scoped to the
 * service lifecycle via [lifecycleScope]; they are automatically cancelled when
 * the service is destroyed.
 */
class WatchMessageService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID      = "wristotle_service"
    }

    private lateinit var transport: PebbleTransport
    private lateinit var registry: HandlerRegistry
    private var dataReceiver: PebbleKit.PebbleDataReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        transport = PebbleTransport(this)

        val contacts = ContactsRepository(this)
        registry = HandlerRegistry(listOf(
            CallHandler(this, contacts),
            SmsHandler(this, contacts),
            // Add new handlers here — e.g. NavigationHandler, WeatherHandler
        ))

        registerPebbleReceiver()
        // Announce to the watch immediately so it knows the companion is running.
        transport.sendReady()
    }

    override fun onDestroy() {
        dataReceiver?.let { transport.unregisterReceiver(it) }
        super.onDestroy()
    }

    /**
     * Registers the PebbleDataReceiver that handles all inbound AppMessages from the watch.
     *
     * companion_ping — watch startup handshake; respond with companion_ready.
     * companion_query — voice transcription to dispatch; response is sent back
     *                   asynchronously on [Dispatchers.IO] to avoid blocking the main thread.
     */
    private fun registerPebbleReceiver() {
        dataReceiver = object : PebbleKit.PebbleDataReceiver(PebbleTransport.PEBBLE_UUID) {
            override fun receiveData(context: Context, transactionId: Int, data: PebbleDictionary) {
                // ACK every message immediately so the watch doesn't retry.
                PebbleKit.sendAckToPebble(context, transactionId)

                if (data.getUnsignedIntegerAsLong(MessageKeys.COMPANION_PING) != null) {
                    transport.sendReady()
                    return
                }

                val query = data.getString(MessageKeys.COMPANION_QUERY) ?: return
                lifecycleScope.launch(Dispatchers.IO) {
                    val response = registry.dispatch(query)
                    transport.sendResponse(response)
                }
            }
        }
        transport.registerReceiver(dataReceiver!!)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.service_notification_title))
        .setContentText(getString(R.string.service_notification_text))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
