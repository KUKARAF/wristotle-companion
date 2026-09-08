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

    /** Send several text tuples in ONE AppMessage frame (atomic — they arrive
     *  in the same inbox iteration). Used to pair a response with its card-kind
     *  hint. Returns true if the watch ACKed. */
    suspend fun sendTexts(texts: Map<UInt, String>): Boolean

    /** Send a single int32 tuple on [key]. Returns true if the watch ACKed. */
    suspend fun sendInt32(key: UInt, value: Int): Boolean

    /** Presence-only ping — sends `1u` as a UInt8 on [key]. */
    suspend fun sendPresence(key: UInt): Boolean

    /**
     * Send a raw byte payload on [key] (optionally with [start]/[end] presence
     * flags in the same AppMessage so the watch can open/close a stream around
     * the chunk in one round-trip). Added for the TTS spike — see
     * `on-watch-tts.md`.
     */
    suspend fun sendTtsChunk(bytes: ByteArray, start: Boolean, end: Boolean): Boolean

    /**
     * One saved-codes sync frame (companion → watch). [index] 0 resets the
     * watch cache; [count] 0 means "no codes, just clear"; [label] is the
     * display name; [matrix] is the CodeWire bytes the watch draws. See
     * `codes/CodeSyncSender`.
     */
    suspend fun sendCodeFrame(index: Int, count: Int, label: String, matrix: ByteArray): Boolean

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
suspend fun WatchTransport.sendForHint(
    hint: Intent?,
    text: String,
    cardKind: String? = null,
    cardData: String? = null,
    success: Boolean = true,
    contextActive: Boolean = false,
): Boolean {
    val responseKey = when (hint) {
        Intent.Reminder -> MessageKeys.REMINDER_RESULT
        Intent.Cancel -> MessageKeys.CANCEL_RESULT
        else -> MessageKeys.COMPANION_RESPONSE
    }
    // Pair the response with its card-kind hint + optional structured card data
    // in ONE frame so the watch can render a full-screen (and visual) card;
    // absent kind ⇒ plain chat bubble. On failure we also pair a result_status
    // flag in the same frame so the watch keeps the failed result on-screen
    // (idle-until-BACK) instead of auto-exiting like a success. Sent only on
    // failure so the success frame stays byte-identical to older releases.
    val payload = buildMap {
        put(responseKey, text)
        if (!cardKind.isNullOrEmpty()) put(MessageKeys.CARD_KIND, cardKind)
        if (!cardData.isNullOrEmpty()) put(MessageKeys.CARD_DATA, cardData)
        if (!success) put(MessageKeys.RESULT_STATUS, MessageKeys.RESULT_STATUS_FAILED)
        if (contextActive) put(MessageKeys.CONTEXT_ACTIVE, MessageKeys.CONTEXT_ACTIVE_ON)
    }
    return if (payload.size == 1) sendText(responseKey, text) else sendTexts(payload)
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
