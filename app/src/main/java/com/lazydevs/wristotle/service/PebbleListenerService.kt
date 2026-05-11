package com.lazydevs.wristotle.service

import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.handlers.CallHandler
import com.lazydevs.wristotle.handlers.HandlerRegistry
import com.lazydevs.wristotle.handlers.SmsHandler
import com.lazydevs.wristotle.phone.ContactsRepository
import com.lazydevs.wristotle.transport.MessageKeys
import com.lazydevs.wristotle.transport.PebbleTransport
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
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

    override fun onCreate() {
        super.onCreate()
        transport = PebbleTransport(this)
        val contacts = ContactsRepository(this)
        registry = HandlerRegistry(listOf(
            CallHandler(this, contacts),
            SmsHandler(this, contacts),
        ))
    }

    override fun onDestroy() {
        transport.close()
        super.onDestroy()
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != AppConstants.PEBBLE_UUID) return ReceiveResult.Ack

        if (data[MessageKeys.COMPANION_PING] != null) {
            transport.sendReady()
            return ReceiveResult.Ack
        }

        val query = (data[MessageKeys.COMPANION_QUERY] as? PebbleDictionaryItem.Text)?.value
            ?: return ReceiveResult.Ack

        val response = registry.dispatch(query)
        transport.sendResponse(response)

        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == AppConstants.PEBBLE_UUID) {
            coroutineScope.launch { transport.sendReady() }
        }
    }
}
