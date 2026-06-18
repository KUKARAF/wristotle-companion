// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.transport

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.AppConstants
import com.lazydevs.wristotle.speech.nlu.transport.MessageKeys
import com.lazydevs.wristotle.speech.nlu.transport.ReminderPin
import com.lazydevs.wristotle.speech.nlu.transport.TimelineSendResult
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineReminder
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay

/**
 * PebbleKit2 implementation of [WatchTransport]. All the named convenience
 * methods (sendResponse, sendForHint, …) live as extension functions in
 * commonMain so this class only has to provide the 3 send primitives plus
 * the 2 timeline ops.
 *
 * The named-method shape of older PebbleTransport call sites still works
 * because the extensions on WatchTransport carry the same names —
 * `transport.sendResponse(text)` continues to compile even though
 * sendResponse is no longer a member.
 */
@OptIn(ExperimentalTime::class)
class PebbleTransport(context: Context) : WatchTransport, java.io.Closeable {

    private val sender = DefaultPebbleSender(context)

    override suspend fun sendText(key: UInt, text: String): Boolean = sendWithNackRetry(
        mapOf(key to PebbleDictionaryItem.Text(text))
    )

    override suspend fun sendTexts(texts: Map<UInt, String>): Boolean = sendWithNackRetry(
        texts.mapValues { PebbleDictionaryItem.Text(it.value) }
    )

    override suspend fun sendInt32(key: UInt, value: Int): Boolean = sendWithNackRetry(
        mapOf(key to PebbleDictionaryItem.Int32(value))
    )

    override suspend fun sendPresence(key: UInt): Boolean = sendWithNackRetry(
        mapOf(key to PebbleDictionaryItem.UInt8(1u))
    )

    override suspend fun sendTtsChunk(bytes: ByteArray, start: Boolean, end: Boolean): Boolean {
        val payload = mutableMapOf<UInt, PebbleDictionaryItem>()
        if (start) payload[MessageKeys.TTS_START] = PebbleDictionaryItem.UInt8(1u)
        if (bytes.isNotEmpty()) payload[MessageKeys.TTS_PCM_CHUNK] = PebbleDictionaryItem.Bytes(bytes)
        if (end) payload[MessageKeys.TTS_END] = PebbleDictionaryItem.UInt8(1u)
        if (payload.isEmpty()) return true
        return sendWithNackRetry(payload)
    }

    override suspend fun insertReminderPin(pin: ReminderPin): TimelineSendResult {
        val startTime = Instant.fromEpochMilliseconds(pin.startEpochMillis)
        return sender.insertTimelinePin(
            AppConstants.PEBBLE_UUID,
            TimelinePin(
                id = pin.id,
                startTime = startTime,
                layout = TimelineLayout(
                    type = TimelineLayoutType.GENERIC_PIN,
                    title = pin.title,
                    tinyIcon = pin.tinyIcon,
                ),
                // A bare pin is silent; attach a reminder so the watch actually
                // buzzes at the reminder time. Requires a host (libpebble3) that
                // forwards TimelinePin.reminders to its timeline emulator.
                reminders = listOf(
                    TimelineReminder(
                        time = startTime,
                        layout = TimelineLayout(
                            type = TimelineLayoutType.GENERIC_REMINDER,
                            title = pin.title,
                            tinyIcon = pin.tinyIcon,
                        ),
                    ),
                ),
            ),
        ).toCommon()
    }

    override suspend fun deleteReminderPin(pinId: String): TimelineSendResult =
        sender.deleteTimelinePin(AppConstants.PEBBLE_UUID, pinId).toCommon()

    private fun TimelineResult.toCommon(): TimelineSendResult = when (this) {
        TimelineResult.Success -> TimelineSendResult.Success
        else -> TimelineSendResult.Failed(toString())
    }

    override fun close() {
        sender.close()
    }

    // Defensive single-retry on watch NACK. With the watch's AppMessage inbox
    // sized at app_message_inbox_size_maximum(), NACKs shouldn't happen in
    // steady state — but transient BLE/AppMessage-state hiccups occasionally
    // bounce a send, and one retry after a short pause clears them.
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
