// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.briefing

import com.lazydevs.wristotle.alarms.AlarmEntity
import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.tasks.TaskEntity
import com.lazydevs.wristotle.briefing.UnreadMessagesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-JUnit tests for the per-section summarisers. The handler's
 * IO concerns (Calendar / Room / SharedPrefs) are out of scope —
 * these exercise format, count gating, and the empty-section nulls.
 */
class MorningBriefRendererTest {

    // ── render() ───────────────────────────────────────────────────

    @Test fun `render drops null sections`() {
        val out = MorningBriefRenderer.render(
            listOf("Meetings: 1 today — Dentist @ 9:00am", null, "Alarms: 1 at 7:00am", null, null),
        )
        assertEquals("Meetings: 1 today — Dentist @ 9:00am\nAlarms: 1 at 7:00am", out)
    }

    @Test fun `render returns EMPTY_BRIEF when everything is null`() {
        assertEquals(
            MorningBriefRenderer.EMPTY_BRIEF,
            MorningBriefRenderer.render(listOf(null, null, null, null, null)),
        )
    }

    // ── meetingsSection ────────────────────────────────────────────

    @Test fun `no meetings returns null`() {
        assertNull(MorningBriefRenderer.meetingsSection(emptyList()))
    }

    @Test fun `one meeting renders singular`() {
        val events = listOf(event("Dentist", at(9, 0)))
        val out = MorningBriefRenderer.meetingsSection(events)!!
        assertTrue(out, out.startsWith("Meetings: 1 today"))
        assertTrue(out, "Dentist" in out)
        assertTrue(out, "9:00am" in out)
    }

    @Test fun `three meetings render plural, no overflow`() {
        val events = listOf(
            event("Standup", at(9, 30)),
            event("1:1", at(14, 0)),
            event("Demo", at(16, 0)),
        )
        val out = MorningBriefRenderer.meetingsSection(events)!!
        assertEquals(
            "Meetings: 3 today — Standup @ 9:30am, 1:1 @ 2:00pm, Demo @ 4:00pm",
            out,
        )
    }

    @Test fun `more than three meetings get a plus-N more tail`() {
        val events = listOf(
            event("A", at(8, 0)),
            event("B", at(9, 0)),
            event("C", at(10, 0)),
            event("D", at(11, 0)),
            event("E", at(12, 0)),
        )
        val out = MorningBriefRenderer.meetingsSection(events)!!
        assertTrue(out, out.startsWith("Meetings: 5 today"))
        assertTrue(out, "+2 more" in out)
    }

    @Test fun `meetings sort by start time regardless of input order`() {
        val out = MorningBriefRenderer.meetingsSection(
            listOf(event("Last", at(16, 0)), event("First", at(9, 0))),
        )!!
        // "First" must appear before "Last" in the output even though
        // it was second in the input list.
        assertTrue(out, out.indexOf("First") < out.indexOf("Last"))
    }

    // ── alarmsSection ──────────────────────────────────────────────

    @Test fun `no alarms returns null`() {
        assertNull(MorningBriefRenderer.alarmsSection(emptyList()))
    }

    @Test fun `single alarm renders singular`() {
        assertEquals(
            "Alarms: 1 at 7:00am",
            MorningBriefRenderer.alarmsSection(listOf(alarm(7, 0, "Wake"))),
        )
    }

    @Test fun `multiple alarms sort by hour and minute`() {
        val out = MorningBriefRenderer.alarmsSection(
            listOf(alarm(8, 0, "C"), alarm(6, 30, "A"), alarm(7, 0, "B")),
        )!!
        assertEquals("Alarms: 3 today — 6:30am, 7:00am, 8:00am", out)
    }

    // ── messagesSection ────────────────────────────────────────────

    private fun snap(messaging: List<UnreadMessagesProvider.Section>, other: Int = 0) =
        UnreadMessagesProvider.Snapshot(messaging = messaging, otherCount = other)

    @Test fun `no messages returns null`() {
        assertNull(MorningBriefRenderer.messagesSection(snap(emptyList(), 0)))
    }

    /** Helper for the common "every message is its own conversation"
     *  case — equal conversations + messages counts. */
    private fun section(label: String, count: Int) =
        UnreadMessagesProvider.Section(label, conversations = count, messages = count)

    @Test fun `single message renders singular`() {
        val out = MorningBriefRenderer.messagesSection(
            snap(listOf(section("WhatsApp", 1))),
        )!!
        assertEquals("Messages: 1 from WhatsApp", out)
    }

    @Test fun `multiple messages from multiple apps`() {
        val out = MorningBriefRenderer.messagesSection(
            snap(listOf(
                section("WhatsApp", 3),
                section("Slack", 2),
                section("Signal", 1),
            )),
        )!!
        assertEquals("Messages: 6 from WhatsApp, Slack, Signal", out)
    }

    @Test fun `more than four apps get a plus N more tail`() {
        val out = MorningBriefRenderer.messagesSection(
            snap(listOf(
                section("WhatsApp", 1),
                section("Slack", 1),
                section("Signal", 1),
                section("Telegram", 1),
                section("Discord", 1),
                section("Messenger", 1),
            )),
        )!!
        assertTrue(out, out.startsWith("Messages: 6 from"))
        assertTrue(out, "+2 more apps" in out)
    }

    @Test fun `other-only count renames the line to Notifications`() {
        val out = MorningBriefRenderer.messagesSection(snap(emptyList(), 3))!!
        assertEquals("Notifications: 3 other", out)
    }

    @Test fun `messaging count stays messaging — other goes to the tail`() {
        // Closes the "Messages: 6" confusion from the on-device test
        // where 2 messaging notifs + 4 other read as "6 messages".
        // Header now reports messaging total; the "+4 other" tail
        // surfaces unmatched notifications honestly.
        val out = MorningBriefRenderer.messagesSection(
            snap(listOf(section("WhatsApp", 2)), other = 4),
        )!!
        assertEquals("Messages: 2 from WhatsApp, +4 other", out)
    }

    // ── conversations + raw messages divergence ───────────────────

    @Test fun `single conversation with multiple messages surfaces raw count`() {
        // One thread, five buzzes — header reads as a single
        // conversation but the parenthetical surfaces the actual
        // volume so the user knows the thread is chatty.
        val out = MorningBriefRenderer.messagesSection(snap(listOf(
            UnreadMessagesProvider.Section("WhatsApp", conversations = 1, messages = 5),
        )))!!
        assertEquals("Messages: 1 conversation (5 msgs) from WhatsApp", out)
    }

    @Test fun `multiple conversations and many messages — both surfaced`() {
        val out = MorningBriefRenderer.messagesSection(snap(listOf(
            UnreadMessagesProvider.Section("WhatsApp", conversations = 2, messages = 12),
            UnreadMessagesProvider.Section("Signal", conversations = 1, messages = 5),
        )))!!
        // 3 conversations + 17 individual messages — both numbers in.
        assertEquals(
            "Messages: 3 conversations (17 msgs) from WhatsApp, Signal",
            out,
        )
    }

    @Test fun `equal conversations and messages drops the parenthetical`() {
        // 2 conversations with 1 message each — the parenthetical
        // would just repeat what the header already says, so it's
        // suppressed for a calmer brief.
        val out = MorningBriefRenderer.messagesSection(snap(listOf(
            UnreadMessagesProvider.Section("WhatsApp", conversations = 1, messages = 1),
            UnreadMessagesProvider.Section("Signal", conversations = 1, messages = 1),
        )))!!
        assertEquals("Messages: 2 from WhatsApp, Signal", out)
    }

    // ── remindersSection ───────────────────────────────────────────

    @Test fun `no reminders returns null`() {
        assertNull(MorningBriefRenderer.remindersSection(emptyList()))
    }

    @Test fun `single reminder renders singular`() {
        assertEquals(
            "Reminders: 1 due today",
            MorningBriefRenderer.remindersSection(listOf(reminder("Buy milk"))),
        )
    }

    @Test fun `multiple reminders render count`() {
        assertEquals(
            "Reminders: 3 due today",
            MorningBriefRenderer.remindersSection(
                listOf(reminder("a"), reminder("b"), reminder("c")),
            ),
        )
    }

    // ── tasksSection ───────────────────────────────────────────────

    @Test fun `no tasks returns null`() {
        assertNull(MorningBriefRenderer.tasksSection(emptyList()))
    }

    @Test fun `pending tasks render count`() {
        assertEquals(
            "Tasks: 4 pending",
            MorningBriefRenderer.tasksSection(
                listOf(task("a"), task("b"), task("c"), task("d")),
            ),
        )
    }

    // ── notesSection ───────────────────────────────────────────────

    @Test fun `no notes returns null`() {
        assertNull(MorningBriefRenderer.notesSection(emptyList()))
    }

    @Test fun `single note quotes the body`() {
        val out = MorningBriefRenderer.notesSection(
            listOf(note("Pick up groceries", at(7, 0))),
        )!!
        assertEquals("Notes: 1 today — \"Pick up groceries\"", out)
    }

    @Test fun `notes truncate long bodies with ellipsis`() {
        val long = "this is a long note body that goes well past the forty char cap we set"
        val out = MorningBriefRenderer.notesSection(listOf(note(long, at(7, 0))))!!
        assertTrue(out, "…" in out)
        // Body inside the quotes must fit the cap.
        val quoted = out.substringAfter('"').substringBeforeLast('"')
        assertTrue("got ${quoted.length}: '$quoted'", quoted.length <= 40)
    }

    @Test fun `multi-line note body collapses to first line`() {
        val multi = "first line\nsecond line\nthird"
        val out = MorningBriefRenderer.notesSection(listOf(note(multi, at(7, 0))))!!
        assertTrue(out, "first line" in out)
        assertTrue(out, "second" !in out)
    }

    @Test fun `multiple notes show count plus newest body`() {
        val older = note("old body", at(6, 0))
        val newer = note("fresh body", at(9, 0))
        val out = MorningBriefRenderer.notesSection(listOf(older, newer))!!
        assertTrue(out, out.startsWith("Notes: 2 today"))
        assertTrue(out, "fresh body" in out)
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun event(title: String, beginMs: Long, durationMin: Int = 60) =
        CalendarRepository.Event(
            title = title,
            begin = beginMs,
            end = beginMs + durationMin * 60_000L,
            location = null,
            allDay = false,
        )

    private fun alarm(hour: Int, minute: Int, label: String) =
        AlarmEntity(
            id = 0,
            hour = hour,
            minute = minute,
            label = label,
            destination = "watch",
            wireEpoch = null,
            enabled = true,
            createdAtEpochMs = 0,
        )

    private fun reminder(title: String) =
        ReminderRecord(id = title, title = title, timeMs = null)

    private fun task(text: String) =
        TaskEntity(
            id = 0,
            text = text,
            completed = false,
            createdAtEpochMs = 0,
            completedAtEpochMs = null,
            source = "test",
        )

    private fun note(body: String, createdAt: Long) =
        Note(id = 0, body = body, createdAtEpochMs = createdAt, source = "test")

    /** Build an epoch-millis timestamp for the given hour/minute today
     *  in the test JVM's default zone. The Renderer formats in default
     *  zone too so the strings line up. */
    private fun at(hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}