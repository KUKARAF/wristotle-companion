package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.transport.PebbleTransport
import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant

private const val TAG = "ReminderHandler"

// Phrases stripped when building the reminder title from a voice transcription.
private val STRIP_PREFIXES = Regex(
    """(?i)^(remind me (to|about|that)?|reminder (to|about)?)\s*""",
)
private val STRIP_TIME_PHRASES = Regex(
    """(?i)\s*(at|in|by|on|next|this|every)\s+[\w\s:.,]+${'$'}""",
)

@OptIn(ExperimentalTime::class)
class ReminderHandler(context: Context, private val transport: PebbleTransport) {

    private val pinStore = PinStore(context)

    suspend fun handle(transcription: String): String {
        Log.d(TAG, "reminder: $transcription")

        val parsed = parseTime(transcription)
        if (parsed == null) {
            Log.d(TAG, "no time found in: $transcription")
            return "Couldn't understand the time"
        }

        val title = buildTitle(transcription)
        Log.d(TAG, "date=${parsed.date}  title=$title")

        val pinId = UUID.randomUUID().toString()
        val pin = TimelinePin(
            id = pinId,
            startTime = parsed.date.toInstant().toKotlinInstant(),
            layout = TimelineLayout(
                type = TimelineLayoutType.GENERIC_PIN,
                title = title,
                tinyIcon = "system://images/NOTIFICATION_REMINDER",
            )
        )

        val result = transport.insertReminder(pin)
        Log.d(TAG, "insertTimelinePin: $result")

        return if (result == TimelineResult.Success) {
            pinStore.save(pinId)
            val formatted = android.text.format.DateFormat.format("MMM d 'at' h:mm a", parsed.date).toString()
            "Reminder set:\n$title\n$formatted"
        } else {
            Log.w(TAG, "insertTimelinePin failed: $result")
            "Failed to set reminder ($result)"
        }
    }

    private fun buildTitle(transcription: String): String =
        transcription
            .replace(STRIP_PREFIXES, "")
            .replace(STRIP_TIME_PHRASES, "")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }
            .ifEmpty { transcription.replaceFirstChar { it.uppercaseChar() } }
}
