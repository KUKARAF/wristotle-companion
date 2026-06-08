// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.transport

import com.lazydevs.wristotle.speech.nlu.Intent

/**
 * Platform-agnostic surface for talking to the watch. The Android impl
 * wraps PebbleKit2's `DefaultPebbleSender`; an iOS impl wraps the
 * libpebble3 sender. Callers (handlers + service routing) depend on
 * this interface so they remain portable.
 *
 * Three send primitives + two timeline ops cover every existing watch
 * conversation; all the named convenience methods
 * ([sendResponse], [sendReminderResult], [sendForHint], …) live as
 * extension functions in this package so they don't have to be
 * re-implemented per platform.
 *
 * R4 batch 3 — interface added; PebbleTransport now implements it.
 * Convenience surface kept for source compatibility, exposed via
 * extension functions instead of interface methods.
 */
interface WatchTransport {
    /** Send a single text tuple on [key]. Returns true if the watch ACKed. */
    suspend fun sendText(key: UInt, text: String): Boolean

    /** Send a single int32 tuple on [key]. Returns true if the watch ACKed. */
    suspend fun sendInt32(key: UInt, value: Int): Boolean

    /** Presence-only ping — sends `1u` as a UInt8 on [key]. */
    suspend fun sendPresence(key: UInt): Boolean

    /** Insert (or replace) a reminder pin on the watch's timeline. */
    suspend fun insertReminderPin(pin: ReminderPin): TimelineSendResult

    /** Delete a reminder pin from the watch's timeline by ID. */
    suspend fun deleteReminderPin(pinId: String): TimelineSendResult
}

/**
 * Portable shape of a reminder timeline pin. The Android impl translates
 * this into a PebbleKit2 `TimelinePin`; an iOS impl translates into the
 * libpebble3 equivalent. Limited to the fields the reminder feature
 * actually uses; expand if a future feature needs more pin shapes.
 */
data class ReminderPin(
    val id: String,
    val title: String,
    val startEpochMillis: Long,
    val tinyIcon: String = DEFAULT_REMINDER_ICON,
) {
    companion object {
        const val DEFAULT_REMINDER_ICON = "system://images/NOTIFICATION_REMINDER"
    }
}

/** Result of a timeline pin op. Distinguishes "watch ACKed it" from the
 *  family of transport failures so the handler can surface the right
 *  copy ("Reminder set" vs "Failed to set reminder"). */
sealed interface TimelineSendResult {
    data object Success : TimelineSendResult
    data class Failed(val message: String) : TimelineSendResult
}

// ── Convenience extensions — pure routing wrappers around the 3 primitives.
//    Live in commonMain so iOS automatically gets the same named surface.

suspend fun WatchTransport.sendResponse(text: String) =
    sendText(MessageKeys.COMPANION_RESPONSE, text)

suspend fun WatchTransport.sendReady() =
    sendPresence(MessageKeys.COMPANION_READY)

suspend fun WatchTransport.sendReminderResult(text: String) =
    sendText(MessageKeys.REMINDER_RESULT, text)

suspend fun WatchTransport.sendCancelResult(text: String) =
    sendText(MessageKeys.CANCEL_RESULT, text)

/**
 * Route a dispatch response back over the channel the watch sent its
 * query on so older firmware that distinguishes the reminder/cancel
 * inboxes still routes the response right.
 */
suspend fun WatchTransport.sendForHint(hint: Intent?, text: String) = when (hint) {
    Intent.Reminder -> sendReminderResult(text)
    Intent.Cancel -> sendCancelResult(text)
    else -> sendResponse(text)
}

suspend fun WatchTransport.sendNotesResponse(text: String) =
    sendText(MessageKeys.NOTES_RESPONSE, text)

suspend fun WatchTransport.sendNoteDetailResponse(text: String) =
    sendText(MessageKeys.NOTE_DETAIL_RESPONSE, text)

suspend fun WatchTransport.sendTasksResponse(text: String) =
    sendText(MessageKeys.TASKS_RESPONSE, text)

suspend fun WatchTransport.sendTaskCompleteResponse(text: String) =
    sendText(MessageKeys.TASK_COMPLETE_RESPONSE, text)

suspend fun WatchTransport.sendConfirmPrompt(text: String) =
    sendText(MessageKeys.CONFIRM_PROMPT, text)

suspend fun WatchTransport.sendAgentStatus(text: String) =
    sendText(MessageKeys.AGENT_STATUS, text)

suspend fun WatchTransport.sendSettingsRequest() =
    sendPresence(MessageKeys.REQUEST_SETTINGS)

suspend fun WatchTransport.sendAlarmSet(epochSeconds: Long, label: String): Boolean {
    val a = sendInt32(MessageKeys.ALARM_SET_EPOCH, epochSeconds.toInt())
    val b = sendText(MessageKeys.ALARM_SET_LABEL, label)
    return a && b
}

suspend fun WatchTransport.sendAlarmCancel(epochSeconds: Long) =
    sendInt32(MessageKeys.ALARM_CANCEL_EPOCH, epochSeconds.toInt())

suspend fun WatchTransport.requestAlarmsList() =
    sendPresence(MessageKeys.ALARMS_REQUEST)

suspend fun WatchTransport.sendShowAlarmsList() =
    sendPresence(MessageKeys.ALARMS_SHOW_LIST)

/** Set a bool setting on the watch as a 4-byte int (0/1) — see
 *  PebbleTransport.sendBoolSetting for the historical rationale. */
suspend fun WatchTransport.sendBoolSetting(key: UInt, value: Boolean) =
    sendInt32(key, if (value) 1 else 0)

suspend fun WatchTransport.sendIntSetting(key: UInt, value: Int) =
    sendInt32(key, value)
