package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Handles [Intent.ListReminders] — reads the local [PinStore] and reports the
 * pending reminders. Read-only; the watch timeline API has no list-pins query,
 * so the PinStore is the source of truth. Formatting + past-due filtering live
 * in the pure [ReminderListFormatter].
 */
class ListRemindersHandler(context: Context) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "list_reminders"
    override val intent: Intent = Intent.ListReminders

    override suspend fun handle(result: IntentResult): String =
        ReminderListFormatter.format(pinStore.all(), System.currentTimeMillis())
}
