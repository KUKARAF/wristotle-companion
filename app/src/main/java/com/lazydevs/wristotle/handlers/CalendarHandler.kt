// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.calendarCount
import com.lazydevs.wristotle.speech.nlu.slots.calendarDate
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.util.Date

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
        // R2 batch 4: calendarDate() now returns Instant (commonMain); convert
        // to Date at this boundary so the rest of this Android-bound handler
        // (EventTimeFormat etc.) doesn't have to change.
        val instant = result.slots.calendarDate()
        return if (instant != null) describeDay(Date(instant.toEpochMilliseconds()))
        else describeUpcoming(result.slots.calendarCount())
    }

    private suspend fun describeDay(date: Date): String {
        val events = calendar.onDay(date.time)
        val dayLabel = EventTimeFormat.day(date)
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
        val time = if (e.allDay) "all day" else EventTimeFormat.time(Date(e.begin))
        val day = if (withDay) EventTimeFormat.day(Date(e.begin)) + " " else ""
        return "• ${e.title} — $day$time"
    }

    /** Day + time for an upcoming event, e.g. "Wed May 21 at 3:00 PM". */
    private fun whenLabel(e: CalendarRepository.Event): String =
        EventTimeFormat.whenLabel(e.begin, e.allDay)

    private fun overflowSuffix(total: Int): String =
        if (total > MAX_LIST) "\n…and ${total - MAX_LIST} more" else ""

    private companion object {
        const val MAX_LIST = 5
    }
}