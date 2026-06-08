// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.calendar

/**
 * Platform-agnostic surface for reading + creating calendar events.
 * Android impl wraps `CalendarContract`; iOS impl will wrap EventKit.
 *
 * R4 batch 6 — formal seam. The two calendar handlers
 * (CalendarHandler + CreateEventHandler) now consume this interface;
 * the existing CalendarRepository in :app/phone implements it.
 */
interface CalendarReader {
    /** Whether the platform's "read calendar" permission is granted. */
    fun hasPermission(): Boolean

    /** Whether the platform's "write calendar" permission is granted. */
    fun hasWritePermission(): Boolean

    /** The next [limit] events from now, within the provider's lookahead window. */
    suspend fun upcoming(limit: Int): List<CalendarEvent>

    /** All events whose `begin` falls within the local day of [dayEpochMs]. */
    suspend fun onDay(dayEpochMs: Long): List<CalendarEvent>

    /**
     * Insert a timed event into the device's primary writable calendar.
     * Returns a [CreateEventResult] distinguishing success, "no writable
     * calendar found", and a generic failure.
     */
    suspend fun createEvent(
        title: String,
        beginEpochMs: Long,
        durationMinutes: Int,
    ): CreateEventResult
}

/**
 * A single calendar event/instance. [begin]/[end] are epoch millis in
 * UTC; renderer code converts to local time when displaying.
 */
data class CalendarEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val location: String?,
    val allDay: Boolean,
)

sealed interface CreateEventResult {
    data class Success(val title: String, val begin: Long, val end: Long) : CreateEventResult

    /** No writable calendar was found on the device. */
    data object NoCalendar : CreateEventResult

    /** Insert returned no row, or threw. */
    data object Failed : CreateEventResult
}
