package com.lazydevs.wristotle.handlers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared date/time formatting for the calendar handlers ([CalendarHandler],
 * [CreateEventHandler]) so the watch-facing strings stay identical across
 * read and create. Formats are short for the watch chat window.
 */
object EventTimeFormat {

    private val TIME_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val DAY_FMT = SimpleDateFormat("EEE MMM d", Locale.getDefault())

    /** "3:00 PM". */
    fun time(d: Date): String = TIME_FMT.format(d)

    /** "Wed May 21". */
    fun day(d: Date): String = DAY_FMT.format(d)

    /** "Wed May 21 at 3:00 PM", or "Wed May 21 (all day)" when [allDay]. */
    fun whenLabel(beginMillis: Long, allDay: Boolean = false): String {
        val d = Date(beginMillis)
        return if (allDay) "${day(d)} (all day)" else "${day(d)} at ${time(d)}"
    }
}
