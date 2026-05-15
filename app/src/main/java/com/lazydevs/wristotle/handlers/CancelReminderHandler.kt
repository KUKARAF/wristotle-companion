package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.transport.PebbleTransport
import io.rebble.pebblekit2.common.model.TimelineResult

private const val TAG = "CancelReminderHandler"

class CancelReminderHandler(context: Context, private val transport: PebbleTransport) {

    private val pinStore = PinStore(context)

    suspend fun handle(transcription: String): String {
        Log.d(TAG, "cancel: $transcription")

        val pinId = pinStore.latest()
            ?: return "No reminders to cancel"

        val result = transport.deleteReminder(pinId)
        Log.d(TAG, "deleteTimelinePin result: $result")

        return if (result is TimelineResult.Success) {
            pinStore.remove(pinId)
            "Reminder cancelled"
        } else {
            "Failed to cancel reminder ($result)"
        }
    }
}
