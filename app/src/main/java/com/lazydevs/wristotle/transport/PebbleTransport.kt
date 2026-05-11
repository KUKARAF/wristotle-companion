package com.lazydevs.wristotle.transport

import android.content.Context
import com.lazydevs.wristotle.AppConstants
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/**
 * Thin wrapper around DefaultPebbleSender that handles all communication
 * with the Wristotle watch app. Call [close] when the owning component is destroyed.
 */
class PebbleTransport(context: Context) : java.io.Closeable {

    private val sender = DefaultPebbleSender(context)

    suspend fun sendResponse(text: String) {
        sender.sendDataToPebble(
            AppConstants.PEBBLE_UUID,
            mapOf(MessageKeys.COMPANION_RESPONSE to PebbleDictionaryItem.Text(text))
        )
    }

    suspend fun sendReady() {
        sender.sendDataToPebble(
            AppConstants.PEBBLE_UUID,
            mapOf(MessageKeys.COMPANION_READY to PebbleDictionaryItem.UInt8(1u))
        )
    }

    override fun close() {
        sender.close()
    }
}
