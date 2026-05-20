package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.nlu.slots.eventAttendee
import com.lazydevs.wristotle.nlu.slots.eventDurationMinutes
import com.lazydevs.wristotle.nlu.slots.eventTime
import com.lazydevs.wristotle.nlu.slots.eventTitle
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

        val title = result.slots.eventTitle() ?: defaultTitle(result.slots.eventAttendee())
        val duration = result.slots.eventDurationMinutes()

        return when (val r = calendar.createEvent(title, start.time, duration)) {
            is CalendarRepository.CreateResult.Success ->
                "Created:\n${r.title}\n${EventTimeFormat.whenLabel(r.begin)}"
            CalendarRepository.CreateResult.NoCalendar ->
                "No writable calendar found on your phone."
            CalendarRepository.CreateResult.Failed ->
                "Couldn't create the event."
        }
    }

    private fun defaultTitle(attendee: String?): String =
        if (attendee.isNullOrBlank()) "Meeting" else "Meeting with $attendee"
}
