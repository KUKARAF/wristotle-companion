// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.phone

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lazydevs.wristotle.speech.nlu.calendar.CalendarEvent
import com.lazydevs.wristotle.speech.nlu.calendar.CalendarReader
import com.lazydevs.wristotle.speech.nlu.calendar.CreateEventResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/** Read + create access to the device calendar via CalendarContract. */
class CalendarRepository(private val context: Context) : CalendarReader {

    /** Returns true if READ_CALENDAR permission has been granted. */
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns true if WRITE_CALENDAR permission has been granted. */
    override fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The next [limit] events starting from now, within [WINDOW_DAYS] ahead,
     * ordered soonest-first. Empty when nothing is scheduled in the window.
     */
    override suspend fun upcoming(limit: Int): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val end = now + WINDOW_DAYS * DAY_MS
        return queryInstances(now, end).take(limit.coerceAtLeast(1))
    }

    /**
     * All events overlapping the calendar day that contains [dayMillis]
     * (local midnight-to-midnight), ordered soonest-first.
     */
    override suspend fun onDay(dayEpochMs: Long): List<CalendarEvent> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dayEpochMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + DAY_MS
        return queryInstances(dayStart, dayEnd)
    }

    /**
     * Queries CalendarContract.Instances between [from] and [to] (epoch
     * millis). Instances is the expanded view — recurring events appear as
     * one row per occurrence — and the time range is encoded in the URI
     * path, not the selection. Runs on IO; ContentResolver is blocking.
     */
    private suspend fun queryInstances(from: Long, to: Long): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()

            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b ->
                ContentUris.appendId(b, from)
                ContentUris.appendId(b, to)
                b.build()
            }
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
            )
            val cursor = context.contentResolver.query(
                uri, projection, null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            ) ?: return@withContext emptyList()

            val events = ArrayList<CalendarEvent>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    events.add(
                        CalendarEvent(
                            title = title,
                            begin = c.getLong(1),
                            end = c.getLong(2),
                            location = c.getString(3)?.takeIf { it.isNotBlank() },
                            allDay = c.getInt(4) != 0,
                        )
                    )
                }
            }
            events
        }

    /**
     * Inserts a timed event starting at [begin] (epoch millis) lasting
     * [durationMinutes], on the device's primary (or first writable)
     * calendar. Runs on IO; ContentResolver is blocking.
     */
    override suspend fun createEvent(
        title: String,
        beginEpochMs: Long,
        durationMinutes: Int,
    ): CreateEventResult = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext CreateEventResult.Failed
        val calendarId = writableCalendarId() ?: return@withContext CreateEventResult.NoCalendar
        val end = beginEpochMs + durationMinutes * 60_000L

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginEpochMs)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull() ?: return@withContext CreateEventResult.Failed
        if (ContentUris.parseId(uri) <= 0) return@withContext CreateEventResult.Failed
        CreateEventResult.Success(title, beginEpochMs, end)
    }

    /**
     * Picks a calendar to write to: the primary one if present, else the
     * first calendar the account owner can contribute to. Null when no
     * writable calendar exists.
     */
    private fun writableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null,
        ) ?: return null
        var primary: Long? = null
        var firstWritable: Long? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isPrimary = c.getInt(1) != 0
                val canWrite = c.getInt(2) >=
                    CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                if (!canWrite) continue
                if (isPrimary) { primary = id; break }
                if (firstWritable == null) firstWritable = id
            }
        }
        return primary ?: firstWritable
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val WINDOW_DAYS = 30L
    }
}