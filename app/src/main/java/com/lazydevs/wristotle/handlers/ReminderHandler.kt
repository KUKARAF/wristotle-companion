// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.handlers.persistent.PersistentReminderScheduler
import com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.transport.PebbleTransport
import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import java.util.Date
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant

private const val TAG = "ReminderHandler"

/**
 * Handles [Intent.Reminder] — creates a Pebble timeline pin scheduled for
 * `slots[SlotKeys.Time]` (a [Date]) with title `slots[SlotKeys.Title]`. Both slots are
 * populated by `ReminderSlots`, which wraps the legacy TimeParser + the
 * title-stripping regex from this file's history.
 */
@OptIn(ExperimentalTime::class)
class ReminderHandler(
    context: Context,
    private val transport: PebbleTransport,
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
        val time = result.slots[SlotKeys.Time] as? Date ?: return "Couldn't set reminder"
        val title = (result.slots[SlotKeys.Title] as? String)?.takeIf { it.isNotBlank() }
            ?: result.rawQuery.replaceFirstChar { it.uppercaseChar() }
        val isPersistent = result.slots[SlotKeys.Persistent] as? Boolean ?: false

        Log.d(TAG, "date=$time  title=$title  persistent=$isPersistent")

        val pinId = UUID.randomUUID().toString()
        val pin = TimelinePin(
            id = pinId,
            startTime = time.toInstant().toKotlinInstant(),
            layout = TimelineLayout(
                type = TimelineLayoutType.GENERIC_PIN,
                title = title,
                tinyIcon = "system://images/NOTIFICATION_REMINDER",
            )
        )

        val pinResult = transport.insertReminder(pin)
        Log.d(TAG, "insertTimelinePin: $pinResult")

        return if (pinResult == TimelineResult.Success) {
            // attemptsRemaining is 0 for non-persistent reminders so phase B's
            // scheduler skips them without an explicit isPersistent check.
            val attemptsRemaining =
                if (isPersistent) defaultMaxAttemptsProvider() else 0
            val record = ReminderRecord(
                id = pinId,
                title = title,
                timeMs = time.time,
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