package com.lazydevs.wristotle.phone

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Read-only access to the device calendar via CalendarContract. */
class CalendarRepository(private val context: Context) {

    /** A single calendar event/instance. [begin]/[end] are epoch millis. */
    data class Event(
        val title: String,
        val begin: Long,
        val end: Long,
        val location: String?,
        val allDay: Boolean,
    )

    /** Returns true if READ_CALENDAR permission has been granted. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The next [limit] events starting from now, within [WINDOW_DAYS] ahead,
     * ordered soonest-first. Empty when nothing is scheduled in the window.
     */
    suspend fun upcoming(limit: Int): List<Event> {
        val now = System.currentTimeMillis()
        val end = now + WINDOW_DAYS * DAY_MS
        return queryInstances(now, end).take(limit.coerceAtLeast(1))
    }

    /**
     * All events overlapping the calendar day that contains [dayMillis]
     * (local midnight-to-midnight), ordered soonest-first.
     */
    suspend fun onDay(dayMillis: Long): List<Event> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dayMillis
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
    private suspend fun queryInstances(from: Long, to: Long): List<Event> =
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

            val events = ArrayList<Event>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    events.add(
                        Event(
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

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val WINDOW_DAYS = 30L
    }
}
