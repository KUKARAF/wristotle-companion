package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.transport.PebbleTransport
import io.rebble.pebblekit2.common.model.TimelineResult

private const val TAG = "CancelReminderHandler"

/**
 * Handles [Intent.Cancel] — deletes the most-recent timeline pin. Today
 * the transcription itself is ignored; future work could honor a
 * `slots["target"]` slot for "cancel my 5 pm reminder" style queries.
 */
class CancelReminderHandler(context: Context, private val transport: PebbleTransport) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "cancel"
    override val intent: Intent = Intent.Cancel

    override suspend fun handle(result: IntentResult): String {
        Log.d(TAG, "cancel: ${result.rawQuery}")

        val target = pinStore.latest()
            ?: return "No reminders to cancel"

        val cancelResult = transport.deleteReminder(target.id)
        Log.d(TAG, "deleteTimelinePin result: $cancelResult")

        return if (cancelResult is TimelineResult.Success) {
            pinStore.remove(target.id)
            "Reminder cancelled"
        } else {
            "Failed to cancel reminder ($cancelResult)"
        }
    }
}
