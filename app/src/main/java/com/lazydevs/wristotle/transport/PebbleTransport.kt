package com.lazydevs.wristotle.transport

import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lazydevs.wristotle.AppConstants
import com.getpebble.android.kit.Constants
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

    /** Sends a query result string back to the watch as MESSAGE_KEY_companion_response. */
    fun sendResponse(text: String) {
        val dict = PebbleDictionary()
        dict.addString(MessageKeys.COMPANION_RESPONSE, text)
        PebbleKit.sendDataToPebble(context, AppConstants.PEBBLE_UUID, dict)
    }

    /**
     * Announces that the companion is alive by sending MESSAGE_KEY_companion_ready.
     * Called on service start and again whenever the watch sends a companion_ping.
     */
    fun sendReady() {
        val dict = PebbleDictionary()
        // Signals "ready"
        dict.addUint8(MessageKeys.COMPANION_READY, AppConstants.PebbleValues.READY_SIGNAL)
        PebbleKit.sendDataToPebble(context, AppConstants.PEBBLE_UUID, dict)
    }

    /** Registers a receiver for inbound AppMessages from the watch. */
    fun registerReceiver(receiver: PebbleKit.PebbleDataReceiver) {
        // Manual registration via ContextCompat to avoid SecurityException on Android 14+ (API 34+)
        val filter = IntentFilter(Constants.INTENT_APP_RECEIVE)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Unregisters a previously registered data receiver. */
    fun unregisterReceiver(receiver: PebbleKit.PebbleDataReceiver) {
        context.unregisterReceiver(receiver)
    }
}
