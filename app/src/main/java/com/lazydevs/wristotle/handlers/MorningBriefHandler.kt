// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import android.util.Log
import com.lazydevs.wristotle.alarms.AlarmRepository
import com.lazydevs.wristotle.briefing.MorningBriefRenderer
import com.lazydevs.wristotle.briefing.TodayRange
import com.lazydevs.wristotle.briefing.UnreadMessagesProvider
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.tasks.TaskRepository

private const val TAG = "MorningBriefHandler"

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
 * Unread messages: two paths, selected by [notifLogEnabledProvider].
 *  - Default (toggle off, default-stance): snapshot of currently-posted
 *    notifications from a curated set of messaging apps
 *    (`MessagingApps`), grouped by app. Snapshot semantics — a
 *    notification the user has already dismissed never appears.
 *  - Persisted log (toggle on, Settings → 🔔 Notifications): replays
 *    `todayPosts()` from the on-device log so a notification swiped
 *    before brief time still shows up. Empty-log fallback to snapshot
 *    catches the "toggle just turned on, no posts logged yet" case.
 *
 * Either way: no notification body / title / extras are read.
 */
class MorningBriefHandler(
    context: Context,
    private val calendar: CalendarRepository,
    private val alarms: AlarmRepository,
    private val tasks: TaskRepository,
    private val notes: NoteRepository,
    private val unreadMessages: UnreadMessagesProvider = UnreadMessagesProvider(),
    private val notifLogEnabledProvider: () -> Boolean = { false },
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

        // Persisted-log path when the user opted in via Settings →
        // 🔔 Notifications: union of the currently-in-tray snapshot AND
        // today's persisted log, deduped by conversation key. Catches
        // both "I dismissed it before brief time" (log) and "it's still
        // in my tray" (snapshot) without losing either.
        val messages = if (notifLogEnabledProvider()) {
            val combined = unreadMessages.snapshotPlusTodayPosts()
            Log.d(TAG, "notif-log path: ${combined.totalConversations} conv / ${combined.totalMessages} msgs (snapshot ∪ log)")
            combined
        } else {
            unreadMessages.snapshot()
        }

        return MorningBriefRenderer.render(
            listOf(
                MorningBriefRenderer.meetingsSection(meetings),
                MorningBriefRenderer.alarmsSection(alarmsToday),
                // Messages sit between time-anchored items (meetings /
                // alarms) and the personal queue (reminders / tasks /
                // notes) — they're current-state, like the calendar
                // line, but transient enough that they drop first when
                // the trim hits.
                MorningBriefRenderer.messagesSection(messages),
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