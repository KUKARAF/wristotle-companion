package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.alarms.AlarmRepository
import com.lazydevs.wristotle.briefing.MorningBriefRenderer
import com.lazydevs.wristotle.briefing.TodayRange
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.tasks.TaskRepository

/**
 * Handles [Intent.MorningBrief] — assembles a one-shot summary of
 * what the user has on their plate today. Pure aggregation over
 * already-existing repositories; no new persistence, no mutations.
 *
 * Sections, in order: meetings → alarms → reminders → tasks → notes.
 * The order matters for trim — if the brief overshoots the watch's
 * ~280 char chat bubble, the renderer drops the LAST section first,
 * keeping calendar / alarms (the time-sensitive stuff) at the top.
 *
 * Calendar permission is optional. When `READ_CALENDAR` isn't
 * granted the meetings section is silently omitted instead of
 * surfacing a permission nag — the user already sees the Calendar
 * card in the Permissions tab if they want to enable it.
 *
 * Unread messages are NOT included in v1. SMS via Telephony +
 * notification-listener counts for WhatsApp / Telegram have their
 * own permission scope and vendor differences; pull them into a
 * follow-up once the rest of the brief feels right on-device.
 */
class MorningBriefHandler(
    context: Context,
    private val calendar: CalendarRepository,
    private val alarms: AlarmRepository,
    private val tasks: TaskRepository,
    private val notes: NoteRepository,
) : ActionHandler {

    override val tag: String = "morning-brief"
    override val intent: Intent = Intent.MorningBrief

    private val pinStore = PinStore(context)

    override suspend fun handle(result: IntentResult): String {
        val today = TodayRange.now()

        val meetings = if (calendar.hasPermission()) {
            calendar.onDay(today.startMs)
        } else emptyList()

        val alarmsToday = alarms.getAll().filter { it.enabled }

        val remindersToday = pinStore.all().filter { rec ->
            rec.timeMs?.let { it in today } ?: false
        }

        val pendingTasks = tasks.listPending()

        // NoteRepository only exposes observeAll() / mostRecent(N).
        // Pulling a generous recent slice + filtering by createdAt
        // gives us today's notes without an extra DAO query.
        val notesToday = notes.mostRecent(MAX_RECENT_NOTES_SCAN)
            .filter { it.createdAtEpochMs in today }

        return MorningBriefRenderer.render(
            listOf(
                MorningBriefRenderer.meetingsSection(meetings),
                MorningBriefRenderer.alarmsSection(alarmsToday),
                MorningBriefRenderer.remindersSection(remindersToday),
                MorningBriefRenderer.tasksSection(pendingTasks),
                MorningBriefRenderer.notesSection(notesToday),
            ),
        )
    }

    private companion object {
        /** Recent-notes window the today filter scans. 50 is generous
         *  for an average user; even bursty note-takers rarely create
         *  this many in one day. */
        const val MAX_RECENT_NOTES_SCAN = 50
    }
}
