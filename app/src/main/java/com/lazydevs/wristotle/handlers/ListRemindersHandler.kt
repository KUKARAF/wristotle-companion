// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.util.Date

/**
 * Handles [Intent.ListReminders] — reads the local [PinStore] and reports the
 * pending reminders. Read-only; the watch timeline API has no list-pins query,
 * so the PinStore is the source of truth. Formatting + past-due filtering live
 * in the pure [ReminderListFormatter].
 *
 * When a `time` slot is present ("is there a reminder at 2pm") it answers for
 * that time; otherwise it lists everything.
 */
class ListRemindersHandler(context: Context) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "list_reminders"
    override val intent: Intent = Intent.ListReminders

    override suspend fun handle(result: IntentResult): String {
        val now = System.currentTimeMillis()
        val all = pinStore.all()
        val time = result.slots[SlotKeys.Time] as? Date
        return if (time != null) {
            ReminderListFormatter.formatAtTime(all, time.time, now)
        } else {
            ReminderListFormatter.format(all, now)
        }
    }
}