package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.handlers.persistent.PersistentReminderScheduler
import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.transport.PebbleTransport
import io.rebble.pebblekit2.common.model.TimelineResult

private const val TAG = "CancelReminderHandler"

/**
 * Handles [Intent.Cancel].
 *
 *  - **Bare cancel** ("cancel that", "cancel my last reminder") has no `target`
 *    slot → cancels the most-recent reminder (original behaviour).
 *  - **Targeted cancel** ("cancel the gym one", "cancel my 5pm") carries a
 *    `target` → cancels the best-matching reminder via [ReminderMatching]. A
 *    target that matches nothing reports not-found rather than falling back to
 *    the latest, so we never delete the wrong reminder on a misheard target.
 */
class CancelReminderHandler(
    context: Context,
    private val transport: PebbleTransport,
    private val persistentScheduler: PersistentReminderScheduler,
) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "cancel"
    override val intent: Intent = Intent.Cancel

    override suspend fun handle(result: IntentResult): String {
        val target = (result.slots[SlotKeys.Target] as? String)?.trim().orEmpty()
        Log.d(TAG, "cancel: ${result.rawQuery} (target='$target')")

        val record = if (target.isEmpty()) {
            pinStore.latest() ?: return "No reminders to cancel"
        } else {
            ReminderMatching.bestMatch(target, pinStore.all(), System.currentTimeMillis())
                ?: return if (mentionsCalendarEvent(target)) {
                    "I can only cancel reminders, not meetings"
                } else {
                    "No reminder matching \"$target\""
                }
        }

        val cancelResult = transport.deleteReminder(record.id)
        Log.d(TAG, "deleteTimelinePin result: $cancelResult")

        return if (cancelResult is TimelineResult.Success) {
            pinStore.remove(record.id)
            // Also kill any pending phone-side nag chain for this pin. No-op
            // for non-persistent records (cancel() looks up a PendingIntent
            // that was never created) and harmless if the chain has already
            // exhausted its attempts.
            persistentScheduler.cancel(record.id)
            if (target.isEmpty() || record.title.isBlank()) "Reminder cancelled"
            else "Cancelled: ${record.title}"
        } else {
            "Failed to cancel reminder ($cancelResult)"
        }
    }
}
