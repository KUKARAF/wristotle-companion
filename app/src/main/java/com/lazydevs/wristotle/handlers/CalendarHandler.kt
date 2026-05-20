package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.nlu.slots.calendarCount
import com.lazydevs.wristotle.nlu.slots.calendarDate
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles [Intent.Calendar] — read-only calendar lookups.
 *
 * Two shapes, picked by the slots populated by `CalendarSlots`:
 *   - `date` present → list that day's events ("do I have anything on Friday").
 *   - else           → list the next `count` upcoming events ("next meeting",
 *                      "next 3 meetings"; count defaults to 1).
 *
 * Responses are kept short for the watch chat — a few lines, one event per
 * line. Fails soft with a permission hint when READ_CALENDAR isn't granted.
 */
class CalendarHandler(private val calendar: CalendarRepository) : ActionHandler {

    override val tag: String = "calendar"
    override val intent: Intent = Intent.Calendar

    override suspend fun handle(result: IntentResult): String {
        if (!calendar.hasPermission()) {
            return "Calendar access not granted.\nEnable it in the Wristotle app."
        }
        val date = result.slots.calendarDate()
        return if (date != null) describeDay(date) else describeUpcoming(result.slots.calendarCount())
    }

    private suspend fun describeDay(date: Date): String {
        val events = calendar.onDay(date.time)
        val dayLabel = DAY_FMT.format(date)
        if (events.isEmpty()) return "Nothing on $dayLabel."
        val header = "${events.size} on $dayLabel:"
        return header + "\n" + events.take(MAX_LIST).joinToString("\n") { line(it, withDay = false) } +
            overflowSuffix(events.size)
    }

    private suspend fun describeUpcoming(count: Int): String {
        val events = calendar.upcoming(count)
        if (events.isEmpty()) return "No upcoming meetings."
        if (count <= 1) {
            val e = events.first()
            return "Next:\n${e.title}\n${whenLabel(e)}"
        }
        return "Next ${events.size}:\n" +
            events.take(MAX_LIST).mapIndexed { i, e -> "${i + 1}. ${e.title} — ${whenLabel(e)}" }
                .joinToString("\n") +
            overflowSuffix(events.size)
    }

    /** "Buy milk at 3:00 PM" — or with the weekday when [withDay]. */
    private fun line(e: CalendarRepository.Event, withDay: Boolean): String {
        val time = if (e.allDay) "all day" else TIME_FMT.format(Date(e.begin))
        val day = if (withDay) DAY_FMT.format(Date(e.begin)) + " " else ""
        return "• ${e.title} — $day$time"
    }

    /** Day + time for an upcoming event, e.g. "Wed May 21 at 3:00 PM". */
    private fun whenLabel(e: CalendarRepository.Event): String =
        if (e.allDay) "${DAY_FMT.format(Date(e.begin))} (all day)"
        else "${DAY_FMT.format(Date(e.begin))} at ${TIME_FMT.format(Date(e.begin))}"

    private fun overflowSuffix(total: Int): String =
        if (total > MAX_LIST) "\n…and ${total - MAX_LIST} more" else ""

    private companion object {
        const val MAX_LIST = 5
        val TIME_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())
        val DAY_FMT = SimpleDateFormat("EEE MMM d", Locale.getDefault())
    }
}
