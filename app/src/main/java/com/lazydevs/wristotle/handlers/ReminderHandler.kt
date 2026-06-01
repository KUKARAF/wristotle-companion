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

private const val TAG = "ReminderHandler"

/**
 * Handles [Intent.Reminder] — creates a Pebble timeline pin scheduled for
 * `slots[SlotKeys.Time]` (a [Date]) with title `slots[SlotKeys.Title]`. Both slots are
 * populated by `ReminderSlots`, which wraps the legacy TimeParser + the
 * title-stripping regex from this file's history.
 */
@OptIn(ExperimentalTime::class)
class ReminderHandler(context: Context, private val transport: PebbleTransport) : ActionHandler {

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

        Log.d(TAG, "date=$time  title=$title")

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
            pinStore.save(ReminderRecord(id = pinId, title = title, timeMs = time.time))
            val formatted = android.text.format.DateFormat.format("MMM d 'at' h:mm a", time).toString()
            "Reminder set:\n$title\n$formatted"
        } else {
            Log.w(TAG, "insertTimelinePin failed: $pinResult")
            "Failed to set reminder ($pinResult)"
        }
    }
}
