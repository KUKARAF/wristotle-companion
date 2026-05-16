package com.lazydevs.wristotle.service

import android.util.Log
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.handlers.CallHandler
import com.lazydevs.wristotle.handlers.CancelReminderHandler
import com.lazydevs.wristotle.handlers.HandlerRegistry
import com.lazydevs.wristotle.handlers.ReminderHandler
import com.lazydevs.wristotle.handlers.SmsHandler
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.transport.MessageKeys
import com.lazydevs.wristotle.transport.PebbleTransport
import com.lazydevs.wristotle.transport.text
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Receives AppMessages from the Pebble watch via rePebble and dispatches them to handlers.
 *
 * Registered in the manifest with the `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` intent
 * filter so rePebble binds to it when a message arrives. Note: received integer values are
 * always delivered as UInt32/Int32 regardless of their original size on the watch.
 */
class PebbleListenerService : BasePebbleListenerService() {

    private lateinit var transport: PebbleTransport
    private lateinit var registry: HandlerRegistry
    private lateinit var reminderHandler: ReminderHandler
    private lateinit var cancelHandler: CancelReminderHandler

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service bound by rePebble")
        transport = (application as WristotleApplication).transport
        val contacts = ContactsRepository(this)
        registry = HandlerRegistry(listOf(
            CallHandler(this, contacts),
            SmsHandler(this, contacts),
        ))
        reminderHandler = ReminderHandler(this, transport)
        cancelHandler = CancelReminderHandler(this, transport)
    }

    // Transport is Application-owned; no close in onDestroy. The base class cancels
    // its coroutineScope during onDestroy(), which terminates any in-flight handler
    // coroutines cleanly before super returns.

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        Log.d(TAG, "Message received from $watchappUUID: $data")
        if (watchappUUID != AppConstants.PEBBLE_UUID) return ReceiveResult.Ack

        if (data[MessageKeys.COMPANION_PING] != null) {
            Log.d(TAG, "Received COMPANION_PING, sending READY")
            transport.sendReady()
            return ReceiveResult.Ack
        }

        val reminderQuery = data.text(MessageKeys.REMINDER_QUERY)
        if (reminderQuery != null) {
            Log.d(TAG, "Reminder query: $reminderQuery")
            val result = reminderHandler.handle(reminderQuery)
            transport.sendReminderResult(result)
            return ReceiveResult.Ack
        }

        val cancelQuery = data.text(MessageKeys.CANCEL_QUERY)
        if (cancelQuery != null) {
            Log.d(TAG, "Cancel query: $cancelQuery")
            val result = cancelHandler.handle(cancelQuery)
            transport.sendCancelResult(result)
            return ReceiveResult.Ack
        }

        val query = data.text(MessageKeys.COMPANION_QUERY)
            ?: return ReceiveResult.Ack

        Log.d(TAG, "Dispatching query: $query")
        val response = registry.dispatch(query)
        Log.d(TAG, "Sending response: $response")
        transport.sendResponse(response)

        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "App opened: $watchappUUID")
        if (watchappUUID == AppConstants.PEBBLE_UUID) {
            coroutineScope.launch { transport.sendReady() }
        }
    }

    companion object {
        private const val TAG = "PebbleListenerService"
    }
}
