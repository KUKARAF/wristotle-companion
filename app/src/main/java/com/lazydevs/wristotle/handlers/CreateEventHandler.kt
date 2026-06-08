// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.eventAttendee
import com.lazydevs.wristotle.speech.nlu.slots.eventDurationMinutes
import com.lazydevs.wristotle.speech.nlu.slots.eventTime
import com.lazydevs.wristotle.speech.nlu.slots.eventTitle
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Handles [Intent.CreateEvent] — writes a new event to the device calendar.
 *
 * Needs a parsed `time` slot; the title defaults to "Meeting" (or "Meeting
 * with <attendee>") when none is spoken, since flaky transcription often
 * drops the title but the time + intent survive. Returns a "Created: …"
 * summary so a misheard time is visible on the watch. Fails soft when
 * WRITE_CALENDAR isn't granted or no writable calendar exists.
 *
 * Note: there is no pre-write confirmation on this branch — the generic
 * confirm-before-dispatch flow (parked on its own branch) wraps every
 * action handler, so once merged this insert is gated automatically.
 */
class CreateEventHandler(private val calendar: CalendarRepository) : ActionHandler {

    override val tag: String = "create_event"
    override val intent: Intent = Intent.CreateEvent

    override suspend fun handle(result: IntentResult): String {
        if (!calendar.hasWritePermission()) {
            return "Calendar write access not granted.\nEnable it in the Wristotle app."
        }
        val start = result.slots.eventTime()
            ?: return "Couldn't understand the time.\nTry \"meeting tomorrow at 3pm\"."

        val title = composeTitle(
            explicit = result.slots.eventTitle(),
            attendee = result.slots.eventAttendee(),
        )
        val duration = result.slots.eventDurationMinutes()

        // R2 batch 4: start is now Instant — convert to epoch millis for the
        // Android calendar repository.
        return when (val r = calendar.createEvent(title, start.toEpochMilliseconds(), duration)) {
            is CalendarRepository.CreateResult.Success ->
                "Created:\n${r.title}\n${EventTimeFormat.whenLabel(r.begin)}"
            CalendarRepository.CreateResult.NoCalendar ->
                "No writable calendar found on your phone."
            CalendarRepository.CreateResult.Failed ->
                "Couldn't create the event."
        }
    }

    /**
     * Title rules for the calendar event:
     *  - Both spoken     → "<explicit> with <attendee>" (so "called standup
     *    with alex" → "Standup with Alex"). Skip the append when the
     *    explicit title already names the attendee — Whisper sometimes
     *    captures the with-clause inside the title regex's greedy tail.
     *  - Only explicit   → "<explicit>".
     *  - Only attendee   → "Meeting with <attendee>".
     *  - Neither         → "Meeting".
     */
    private fun composeTitle(explicit: String?, attendee: String?): String = when {
        explicit != null && attendee != null && !explicit.contains(attendee, ignoreCase = true) ->
            "$explicit with $attendee"
        explicit != null -> explicit
        !attendee.isNullOrBlank() -> "Meeting with $attendee"
        else -> "Meeting"
    }
}