// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import kotlinx.datetime.Instant
import com.lazydevs.wristotle.speech.nlu.reminders.ReminderMatching
import com.lazydevs.wristotle.speech.nlu.reminders.mentionsCalendarEvent
import com.lazydevs.wristotle.speech.nlu.reminders.ReminderRecord
import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.handlers.persistent.PersistentReminderScheduler
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.transport.ReminderPin
import com.lazydevs.wristotle.speech.nlu.transport.TimelineSendResult
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import java.util.Date
import java.util.UUID

private const val TAG = "RescheduleHandler"

/**
 * Handles [Intent.Reschedule] — moves an existing reminder to a new time.
 *
 * Resolves the target reminder (the most recent when none is named, else the
 * best [ReminderMatching] hit), deletes its timeline pin, and re-inserts a pin
 * with the same title at the new time. A named target that matches nothing
 * reports not-found rather than moving the wrong reminder.
 *
 * The new time is relative to *now* ("in 10 minutes" = 10 minutes from now),
 * not to the original reminder's time.
 */
class RescheduleHandler(
    context: Context,
    private val transport: WatchTransport,
    private val persistentScheduler: PersistentReminderScheduler,
) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "reschedule"
    override val intent: Intent = Intent.Reschedule

    override suspend fun handle(result: IntentResult): String {
        val instant = result.slots[SlotKeys.Time] as? Instant
            ?: return "Couldn't understand the new time"
        val timeMs = instant.toEpochMilliseconds()
        val time = Date(timeMs)
        val target = (result.slots[SlotKeys.Target] as? String)?.trim().orEmpty()
        Log.d(TAG, "reschedule: ${result.rawQuery} (target='$target' time=$time)")

        val record = if (target.isEmpty()) {
            pinStore.latest() ?: return "No reminders to reschedule"
        } else {
            ReminderMatching.bestMatch(target, pinStore.all(), System.currentTimeMillis())
                ?: return if (mentionsCalendarEvent(target)) {
                    "I can only reschedule reminders, not meetings"
                } else {
                    "No reminder matching \"$target\""
                }
        }

        val deleteResult = transport.deleteReminderPin(record.id)
        if (deleteResult !is TimelineSendResult.Success) {
            Log.w(TAG, "delete during reschedule failed: $deleteResult")
            return "Failed to reschedule ($deleteResult)"
        }
        pinStore.remove(record.id)
        // Kill the phone-side nag chain tied to the old pin id; a fresh one
        // re-arms below if the record was persistent.
        persistentScheduler.cancel(record.id)

        val newId = UUID.randomUUID().toString()
        val pin = ReminderPin(
            id = newId,
            title = record.title,
            startEpochMillis = timeMs,
        )

        val insertResult = transport.insertReminderPin(pin)
        Log.d(TAG, "re-insert result: $insertResult")

        return if (insertResult is TimelineSendResult.Success) {
            // Carry forward persistence so a "remind me at 3pm" → "make that
            // 4pm" doesn't quietly downgrade a persistent reminder. The
            // attempts counter resets too — the new pin starts a fresh chain.
            val moved = ReminderRecord(
                id = newId,
                title = record.title,
                timeMs = timeMs,
                isPersistent = record.isPersistent,
                attemptsRemaining = record.attemptsRemaining,
            )
            pinStore.save(moved)
            if (moved.isPersistent) persistentScheduler.schedule(moved)
            val formatted = android.text.format.DateFormat.format("MMM d 'at' h:mm a", time).toString()
            "Moved: ${record.title}\n$formatted"
        } else {
            Log.w(TAG, "re-insert failed: $insertResult")
            "Failed to reschedule ($insertResult)"
        }
    }
}