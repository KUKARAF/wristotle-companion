package com.lazydevs.wristotle.transport

import android.content.Context
import com.lazydevs.wristotle.AppConstants
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult

class PebbleTransport(context: Context) : java.io.Closeable {

    private val sender = DefaultPebbleSender(context)

    suspend fun sendResponse(text: String) = sender.sendDataToPebble(
        AppConstants.PEBBLE_UUID,
        mapOf(MessageKeys.COMPANION_RESPONSE to PebbleDictionaryItem.Text(text))
    )

    suspend fun sendReady() = sender.sendDataToPebble(
        AppConstants.PEBBLE_UUID,
        mapOf(MessageKeys.COMPANION_READY to PebbleDictionaryItem.UInt8(1u))
    )

    suspend fun sendReminderResult(text: String) = sender.sendDataToPebble(
        AppConstants.PEBBLE_UUID,
        mapOf(MessageKeys.REMINDER_RESULT to PebbleDictionaryItem.Text(text))
    )

    suspend fun sendCancelResult(text: String) = sender.sendDataToPebble(
        AppConstants.PEBBLE_UUID,
        mapOf(MessageKeys.CANCEL_RESULT to PebbleDictionaryItem.Text(text))
    )

    suspend fun insertReminder(pin: TimelinePin): TimelineResult =
        sender.insertTimelinePin(AppConstants.PEBBLE_UUID, pin)

    suspend fun deleteReminder(pinId: String): TimelineResult =
        sender.deleteTimelinePin(AppConstants.PEBBLE_UUID, pinId)

    override fun close() {
        sender.close()
    }
}
