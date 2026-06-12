// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

/**
 * App-generated placeholder titles used when the user didn't dictate one —
 * a time-only reminder ("set a reminder for 8 p.m."), a bare "schedule a
 * meeting at 3", an alarm (which carries no spoken label today), etc.
 *
 * All are prefixed with "Wristotle" so an auto-created entry is recognisable
 * wherever it surfaces — the watch pin, the phone notification, the calendar,
 * the alarm list, and the confirm-before-send preview. An explicit title the
 * user actually spoke is never prefixed.
 */
object DefaultTitles {
    const val PREFIX = "Wristotle"
    const val REMINDER = "$PREFIX Reminder"
    const val MEETING = "$PREFIX Meeting"
    const val ALARM = "$PREFIX Alarm"

    /**
     * The reminder title to save + display, given the user's spoken title (or
     * null/blank when none). Single source of truth — both [ReminderHandler]
     * and the confirm-before-send preview call this, so they can never drift.
     */
    fun composeReminderTitle(explicit: String?): String =
        explicit?.takeIf { it.isNotBlank() } ?: REMINDER

    /**
     * The calendar-event title, given the spoken title and the "with <attendee>"
     * context. Single source of truth for [CreateEventHandler] and the confirm
     * preview.
     *
     *  - Both spoken   → "<explicit> with <attendee>" (skip the append when the
     *    explicit title already names the attendee — Whisper sometimes captures
     *    the with-clause inside the title regex's greedy tail).
     *  - Only explicit → "<explicit>".
     *  - Only attendee → "Meeting with <attendee>" (attendee is context, so no
     *    "Wristotle" prefix — only the fully contextless case gets that).
     *  - Neither       → "Wristotle Meeting".
     */
    fun composeEventTitle(explicit: String?, attendee: String?): String = when {
        !explicit.isNullOrBlank() && !attendee.isNullOrBlank() &&
            !explicit.contains(attendee, ignoreCase = true) -> "$explicit with $attendee"
        !explicit.isNullOrBlank() -> explicit
        !attendee.isNullOrBlank() -> "Meeting with $attendee"
        else -> MEETING
    }
}
