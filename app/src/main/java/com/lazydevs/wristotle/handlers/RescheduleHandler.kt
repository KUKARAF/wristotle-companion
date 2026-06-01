package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.nlu.slots.SlotKeys
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
@OptIn(ExperimentalTime::class)
class RescheduleHandler(context: Context, private val transport: PebbleTransport) : ActionHandler {

    private val pinStore = PinStore(context)

    override val tag: String = "reschedule"
    override val intent: Intent = Intent.Reschedule

    override suspend fun handle(result: IntentResult): String {
        val time = result.slots[SlotKeys.Time] as? Date
            ?: return "Couldn't understand the new time"
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

        val deleteResult = transport.deleteReminder(record.id)
        if (deleteResult !is TimelineResult.Success) {
            Log.w(TAG, "delete during reschedule failed: $deleteResult")
            return "Failed to reschedule ($deleteResult)"
        }
        pinStore.remove(record.id)

        val newId = UUID.randomUUID().toString()
        val pin = TimelinePin(
            id = newId,
            startTime = time.toInstant().toKotlinInstant(),
            layout = TimelineLayout(
                type = TimelineLayoutType.GENERIC_PIN,
                title = record.title,
                tinyIcon = "system://images/NOTIFICATION_REMINDER",
            ),
        )

        val insertResult = transport.insertReminder(pin)
        Log.d(TAG, "re-insert result: $insertResult")

        return if (insertResult == TimelineResult.Success) {
            pinStore.save(ReminderRecord(id = newId, title = record.title, timeMs = time.time))
            val formatted = android.text.format.DateFormat.format("MMM d 'at' h:mm a", time).toString()
            "Moved: ${record.title}\n$formatted"
        } else {
            Log.w(TAG, "re-insert failed: $insertResult")
            "Failed to reschedule ($insertResult)"
        }
    }
}
