package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Pure-function tests for the confirm prompt summary. The body line is what
 * the user reads on the watch before SELECT-ing to dispatch a destructive
 * command, so every branch needs to render something meaningful — never
 * "?" for an intent the handler would actually run.
 */
class ConfirmSummaryBuilderTest {

    // ── Sms (the most-tested branch — most edge cases live here) ──────────

    @Test fun smsContactAndBody() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Sms, "contact" to "mom", "body" to "hi"),
        )
        assertEquals("action: text\nbody: [mom] hi", s)
    }

    @Test fun smsWithoutBody() {
        // Whisper sometimes hears only the contact ("text mom"). Body row
        // shows just the contact in brackets — no trailing punctuation.
        val s = ConfirmSummaryBuilder.summary(result(Intent.Sms, "contact" to "mom"))
        assertEquals("action: text\nbody: [mom]", s)
    }

    @Test fun smsBodyTruncatesAt80Chars() {
        val longBody = "a".repeat(120)
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Sms, "contact" to "x", "body" to longBody),
        )
        // body field rendered as `body: [x] aaaa...` — verify the body
        // portion was capped at 80 chars, not the full 120.
        assertTrue("expected 80-char body, got: $s", s.contains("a".repeat(80)))
        assertTrue("expected NO 81-char body", !s.contains("a".repeat(81)))
    }

    @Test fun smsMissingContactShowsQuestionMark() {
        // SmsSlots produces no contact in the fallback case. Render as `[?]`
        // so the user sees something to react to (and the prompt doesn't
        // render as a misleading empty list).
        val s = ConfirmSummaryBuilder.summary(result(Intent.Sms, "body" to "hi"))
        assertEquals("action: text\nbody: [?] hi", s)
    }

    // ── Call ──────────────────────────────────────────────────────────────

    @Test fun call() {
        val s = ConfirmSummaryBuilder.summary(result(Intent.Call, "contact" to "alex"))
        assertEquals("action: call\nbody: [alex]", s)
    }

    @Test fun callMissingContactShowsQuestionMark() {
        val s = ConfirmSummaryBuilder.summary(result(Intent.Call))
        assertEquals("action: call\nbody: [?]", s)
    }

    // ── Reminder / CreateEvent (title + time) ─────────────────────────────

    @Test fun reminderWithTitleAndTime() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reminder, "title" to "buy milk", "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reminder\nbody: buy milk @ "))
    }

    @Test fun reminderWithoutTitleUsesDefault() {
        // ReminderHandler defaults to no-fixed-title-but-fails-on-no-time.
        // Confirm body uses a generic placeholder so it reads as something
        // rather than "?".
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reminder, "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reminder\nbody: Reminder @ "))
    }

    @Test fun reminderWithoutTime() {
        // Time slot missing — handler will fail with "Couldn't understand
        // the time". The confirm at least surfaces the title so the user
        // knows what was misheard.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reminder, "title" to "gym"),
        )
        assertEquals("action: reminder\nbody: gym", s)
    }

    @Test fun createEventDefaultsTitleToMeeting() {
        // CreateEventHandler defaults title to "Meeting" when no "called X"
        // clause is present. Confirm must mirror that so it doesn't show "?".
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.CreateEvent, "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: schedule\nbody: Meeting @ "))
    }

    @Test fun createEventUsesScheduleVerb() {
        // The action label is "schedule", not "create-event" — matches the
        // verb the user said and reads naturally on the watch.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.CreateEvent, "title" to "Standup", "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: schedule\n"))
        assertTrue(s.contains("body: Standup @ "))
    }

    // ── Cancel / Reschedule ───────────────────────────────────────────────

    @Test fun cancelWithoutTargetSaysLatestReminder() {
        // Bare cancels carry no target slot — the handler cancels the
        // most-recent pin. Confirm must spell that out so the user
        // doesn't see "?" and think nothing's selected.
        val s = ConfirmSummaryBuilder.summary(result(Intent.Cancel))
        assertEquals("action: cancel\nbody: latest reminder", s)
    }

    @Test fun cancelWithNamedTarget() {
        val s = ConfirmSummaryBuilder.summary(result(Intent.Cancel, "target" to "gym"))
        assertEquals("action: cancel\nbody: gym", s)
    }

    @Test fun rescheduleTargetAndTime() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reschedule, "target" to "gym", "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reschedule\nbody: gym → "))
    }

    @Test fun rescheduleWithoutTargetUsesLatest() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reschedule, "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reschedule\nbody: latest reminder → "))
    }

    // ── Open / Play ───────────────────────────────────────────────────────

    @Test fun openApp() {
        assertEquals(
            "action: open\nbody: spotify",
            ConfirmSummaryBuilder.summary(result(Intent.OpenApp, "app" to "spotify")),
        )
    }

    @Test fun mediaPlayWithApp() {
        assertEquals(
            "action: play\nbody: audible",
            ConfirmSummaryBuilder.summary(result(Intent.MediaPlay, "app" to "audible")),
        )
    }

    // ── Read-only intents (still rendered for the preview log) ────────────

    @Test fun readOnlyIntentsRenderWithDash() {
        // Even though these never gate-on-confirm, the same builder is used
        // for the Phase A1.5 debug log. They should render cleanly, not "?".
        assertEquals("action: time\nbody: -", ConfirmSummaryBuilder.summary(result(Intent.Time)))
        assertEquals("action: battery\nbody: -", ConfirmSummaryBuilder.summary(result(Intent.Battery)))
        assertEquals("action: find-phone\nbody: -", ConfirmSummaryBuilder.summary(result(Intent.FindPhone)))
    }

    @Test fun unknownFallsBackToRawQuery() {
        val s = ConfirmSummaryBuilder.summary(
            IntentResult(
                intent = Intent.Unknown,
                slots = emptyMap(),
                confidence = 0.0f,
                alternates = emptyList(),
                rawQuery = "an audiobook.",
            ),
        )
        assertEquals("action: unknown\nbody: an audiobook.", s)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun result(intent: Intent, vararg slots: Pair<String, Any>) =
        IntentResult(
            intent = intent,
            slots = slots.toMap(),
            confidence = 0.9f,
            alternates = emptyList(),
            rawQuery = "",
        )

    private fun fixedDate(): Date = Date(0L)  // any non-null Date triggers the time branch
}
