package com.lazydevs.wristotle.transport

import android.content.Context
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import java.util.UUID

/**
 * Thin wrapper around PebbleKit that handles all Bluetooth communication
 * with the Wristotle watch app.
 *
 * All sends are fire-and-forget — PebbleKit queues them internally and
 * delivers ACKs/NACKs asynchronously, which we don't need to track here.
 */
class PebbleTransport(private val context: Context) {

    companion object {
        /** UUID must match the `uuid` field in the watch app's package.json. */
        val PEBBLE_UUID: UUID = UUID.fromString("a48bf4be-be56-4afb-97a6-5a72ed2f0643")
    }

    /** Sends a query result string back to the watch as MESSAGE_KEY_companion_response. */
    fun sendResponse(text: String) {
        val dict = PebbleDictionary()
        dict.addString(MessageKeys.COMPANION_RESPONSE, text)
        PebbleKit.sendDataToPebble(context, PEBBLE_UUID, dict)
    }

    /**
     * Announces that the companion is alive by sending MESSAGE_KEY_companion_ready.
     * Called on service start and again whenever the watch sends a companion_ping.
     */
    fun sendReady() {
        val dict = PebbleDictionary()
        // addUint8 expects a Short; 1 signals "ready"
        dict.addUint8(MessageKeys.COMPANION_READY, 1.toShort())
        PebbleKit.sendDataToPebble(context, PEBBLE_UUID, dict)
    }

    /** Registers a receiver for inbound AppMessages from the watch. */
    fun registerReceiver(receiver: PebbleKit.PebbleDataReceiver) {
        PebbleKit.registerDataReceiver(context, receiver)
    }

    /** Unregisters a previously registered data receiver. */
    fun unregisterReceiver(receiver: PebbleKit.PebbleDataReceiver) {
        PebbleKit.unregisterDataReceiver(context, receiver)
    }
}
