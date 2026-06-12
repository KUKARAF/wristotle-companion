// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.handlers.DefaultTitles
import com.lazydevs.wristotle.speech.nlu.reminders.ReminderRecord
import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.handlers.persistent.PersistentReminderScheduler
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.boolSlot
import com.lazydevs.wristotle.speech.nlu.instantSlot
import com.lazydevs.wristotle.speech.nlu.optStringSlot
import com.lazydevs.wristotle.speech.nlu.transport.ReminderPin
import com.lazydevs.wristotle.speech.nlu.transport.TimelineSendResult
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport
import java.util.Date
import java.util.UUID
import kotlinx.datetime.Instant

private const val TAG = "ReminderHandler"

/**
 * Handles [Intent.Reminder] — creates a Pebble timeline pin scheduled for
 * `slots[SlotKeys.Time]` (a [Date]) with title `slots[SlotKeys.Title]`. Both slots are
 * populated by `ReminderSlots`, which wraps the legacy TimeParser + the
 * title-stripping regex from this file's history.
 */
class ReminderHandler(
    context: Context,
    private val transport: WatchTransport,
    private val persistentScheduler: PersistentReminderScheduler,
    private val defaultMaxAttemptsProvider: () -> Int = { ReminderSettings.DEFAULT_MAX_ATTEMPTS },
) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "reminder"
    override val intent: Intent = Intent.Reminder

    override suspend fun handle(result: IntentResult): String {
        // ReminderSlots always populates a time — defaults to now + 30 min
        // when no explicit time was spoken — so this cast won't fail in
        // practice. Defensive null-check stays for the type system only.
        val instant = result.instantSlot(SlotKeys.Time) ?: return "Couldn't set reminder"
        val timeMs = instant.toEpochMilliseconds()
        val time = Date(timeMs)
        // ReminderSlots already blanks a time-only / contextless title; the
        // shared resolver then supplies the "Wristotle Reminder" placeholder
        // (same one the confirm preview uses) rather than echoing the raw query.
        val title = DefaultTitles.composeReminderTitle(result.optStringSlot(SlotKeys.Title))
        val isPersistent = result.boolSlot(SlotKeys.Persistent)

        Log.d(TAG, "date=$time  title=$title  persistent=$isPersistent")

        val pinId = UUID.randomUUID().toString()
        val pin = ReminderPin(
            id = pinId,
            title = title,
            startEpochMillis = timeMs,
        )

        val pinResult = transport.insertReminderPin(pin)
        Log.d(TAG, "insertTimelinePin: $pinResult")

        return if (pinResult is TimelineSendResult.Success) {
            // attemptsRemaining is 0 for non-persistent reminders so phase B's
            // scheduler skips them without an explicit isPersistent check.
            val attemptsRemaining =
                if (isPersistent) defaultMaxAttemptsProvider() else 0
            val record = ReminderRecord(
                id = pinId,
                title = title,
                timeMs = timeMs,
                isPersistent = isPersistent,
                attemptsRemaining = attemptsRemaining,
            )
            pinStore.save(record)
            if (isPersistent) persistentScheduler.schedule(record)
            val formatted = android.text.format.DateFormat.format("MMM d 'at' h:mm a", time).toString()
            val prefix = if (isPersistent) "Persistent reminder set" else "Reminder set"
            "$prefix:\n$title\n$formatted"
        } else {
            Log.w(TAG, "insertTimelinePin failed: $pinResult")
            "Failed to set reminder ($pinResult)"
        }
    }
}