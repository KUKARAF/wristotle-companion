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

    /** Notes-on-watch (Phase C): ship the joined notes payload back to the
     *  watch in response to a NOTES_REQUEST. */
    suspend fun sendNotesResponse(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.NOTES_RESPONSE to PebbleDictionaryItem.Text(text))
    )

    /** Ship a single note's full body in response to NOTE_DETAIL_REQUEST. */
    suspend fun sendNoteDetailResponse(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.NOTE_DETAIL_RESPONSE to PebbleDictionaryItem.Text(text))
    )

    /** Tasks-on-watch (Phase B): framed list of pending tasks for the
     *  watch's tasks window in response to a TASKS_REQUEST. */
    suspend fun sendTasksResponse(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.TASKS_RESPONSE to PebbleDictionaryItem.Text(text))
    )

    /** Confirmation back to the watch after a TASK_COMPLETE_REQUEST —
     *  either *"Completed: X"* on success or a graceful failure
     *  ("No pending tasks", "Task already completed"). The watch
     *  displays this in the existing chat surface. */
    suspend fun sendTaskCompleteResponse(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.TASK_COMPLETE_RESPONSE to PebbleDictionaryItem.Text(text))
    )

    /** Ship the confirm-prompt summary to the watch (Phase A3+). The watch
     *  displays it under the existing chat surface and waits for SELECT/BACK;
     *  the response arrives back via [MessageKeys.CONFIRM_RESPONSE] which the
     *  listener service consumes to dispatch the stashed [IntentResult]. */
    suspend fun sendConfirmPrompt(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.CONFIRM_PROMPT to PebbleDictionaryItem.Text(text))
    )

    /** Mid-query agent-loop status (AskAgent B3) — short text shown in the
     *  watch's hint bar so the user sees which tool is in flight without
     *  it clobbering the eventual chat answer. The watch's
     *  [chat_ui_set_thinking(false)] on response arrival restores the
     *  normal hint, clearing the status automatically. */
    suspend fun sendAgentStatus(text: String) = sendWithNackRetry(
        mapOf(MessageKeys.AGENT_STATUS to PebbleDictionaryItem.Text(text))
    )

    suspend fun insertReminder(pin: TimelinePin): TimelineResult =
        sender.insertTimelinePin(AppConstants.PEBBLE_UUID, pin)

    suspend fun deleteReminder(pinId: String): TimelineResult =
        sender.deleteTimelinePin(AppConstants.PEBBLE_UUID, pinId)

    /** Presence-only message — the watch responds by shipping all settings back. */
    suspend fun sendSettingsRequest(): Boolean = sendWithNackRetry(
        mapOf(MessageKeys.REQUEST_SETTINGS to PebbleDictionaryItem.UInt8(1u))
    )

    /** Set a bool setting on the watch as a 4-byte int (0/1). The watch reads
     *  every settings tuple via `prv_tuple_as_int` → `t->value->int32`, i.e. it
     *  always reads 4 bytes; sending a 1-byte `UInt8` makes it read 3 bytes of
     *  adjacent dict memory and mis-store the value. Int32 matches both the
     *  reader and the PKJS/Clay convention. Returns true if the watch ACKed. */
    suspend fun sendBoolSetting(key: UInt, value: Boolean): Boolean = sendWithNackRetry(
        mapOf(key to PebbleDictionaryItem.Int32(if (value) 1 else 0))
    )

    /** Set an int setting on the watch (target enums + quick-launch auto-exit
     *  seconds). Watch reads these via `prv_tuple_as_int` which accepts both
     *  int and uint, so int32 is the safe choice. Returns true if ACKed. */
    suspend fun sendIntSetting(key: UInt, value: Int): Boolean = sendWithNackRetry(
        mapOf(key to PebbleDictionaryItem.Int32(value))
    )

    override fun close() {
        sender.close()
    }

    // Defensive single-retry on watch NACK. With the watch's AppMessage inbox
    // sized at app_message_inbox_size_maximum(), NACKs shouldn't happen in
    // steady state — but transient BLE/AppMessage-state hiccups occasionally
    // bounce a send, and one retry after a short pause clears them. Returns
    // true when the watch ACKed (a NACK means the Wristotle watch app wasn't
    // running to receive it, or the BLE link dropped).
    private suspend fun sendWithNackRetry(data: PebbleDictionary): Boolean {
        val first = sender.sendDataToPebble(AppConstants.PEBBLE_UUID, data)
        if (first?.values?.any { it is TransmissionResult.FailedWatchNacked } != true) return true
        Log.w(TAG, "Watch NACKed send; retrying after ${NACK_RETRY_DELAY_MS}ms")
        delay(NACK_RETRY_DELAY_MS)
        val second = sender.sendDataToPebble(AppConstants.PEBBLE_UUID, data)
        if (second?.values?.any { it is TransmissionResult.FailedWatchNacked } == true) {
            Log.e(TAG, "Watch NACKed retry too — giving up")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "PebbleTransport"
        private const val NACK_RETRY_DELAY_MS = 300L
    }
}
