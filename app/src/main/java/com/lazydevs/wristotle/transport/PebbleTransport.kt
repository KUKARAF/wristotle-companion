package com.lazydevs.wristotle.transport

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.AppConstants
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.delay

class PebbleTransport(context: Context) : java.io.Closeable {

    private val sender = DefaultPebbleSender(context)

    suspend fun sendResponse(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.COMPANION_RESPONSE to PebbleDictionaryItem.Text(text))
    )

    suspend fun sendReady() = sendWithNackRetry(
        mapOf(MessageKeys.COMPANION_READY to PebbleDictionaryItem.UInt8(1u))
    )

    suspend fun sendReminderResult(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.REMINDER_RESULT to PebbleDictionaryItem.Text(text))
    )

    suspend fun sendCancelResult(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.CANCEL_RESULT to PebbleDictionaryItem.Text(text))
    )

    suspend fun insertReminder(pin: TimelinePin): TimelineResult =
        sender.insertTimelinePin(AppConstants.PEBBLE_UUID, pin)

    suspend fun deleteReminder(pinId: String): TimelineResult =
        sender.deleteTimelinePin(AppConstants.PEBBLE_UUID, pinId)

    override fun close() {
        sender.close()
    }

    // Defensive single-retry on watch NACK. With the watch's AppMessage inbox
    // sized at app_message_inbox_size_maximum(), NACKs shouldn't happen in
    // steady state — but transient BLE/AppMessage-state hiccups occasionally
    // bounce a send, and one retry after a short pause clears them.
    private suspend fun sendWithNackRetry(data: PebbleDictionary) {
        val first = sender.sendDataToPebble(AppConstants.PEBBLE_UUID, data)
        if (first?.values?.any { it is TransmissionResult.FailedWatchNacked } != true) return
        Log.w(TAG, "Watch NACKed send; retrying after ${NACK_RETRY_DELAY_MS}ms")
        delay(NACK_RETRY_DELAY_MS)
        val second = sender.sendDataToPebble(AppConstants.PEBBLE_UUID, data)
        if (second?.values?.any { it is TransmissionResult.FailedWatchNacked } == true) {
            Log.e(TAG, "Watch NACKed retry too — giving up")
        }
    }

    companion object {
        private const val TAG = "PebbleTransport"
        private const val NACK_RETRY_DELAY_MS = 300L
    }
}
