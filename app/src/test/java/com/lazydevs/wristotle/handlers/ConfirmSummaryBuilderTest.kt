package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.phone.ContactsRepository
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

    // ── SMS-style body shape (now under SendMessage, app="SMS") ──────────
    // The SendMessage branch tests below carry the full coverage of
    // edge cases (body truncation, missing contact). SMS uses app="SMS"
    // and is rendered with the verb "text" so the prompt matches what
    // the user dictated.

    @Test fun sendMessageWithSmsBodyTruncatesAt80Chars() {
        val longBody = "a".repeat(120)
        val s = ConfirmSummaryBuilder.summary(
            result(
                Intent.SendMessage,
                "app" to "SMS",
                "contact" to "x",
                "resolvedContact" to ContactsRepository.Contact("x", "555-0100"),
                "body" to longBody,
            ),
        )
        assertTrue("expected 80-char body, got: $s", s.contains("a".repeat(80)))
        assertTrue("expected NO 81-char body", !s.contains("a".repeat(81)))
    }

    @Test fun sendMessageWithUnresolvedContactEchoesSpokenAlongsideSentinel() {
        // The spoken contact ("dadd") didn't match any Contacts row, so
        // PebbleListenerService.enrichResolvedContact didn't populate
        // `resolvedContact`. The watch confirm prompt must surface the
        // `[NO_CONTACT]` sentinel AND the spoken string in a second
        // bracketed token, so the user sees WHY the lookup failed
        // (e.g. Whisper transcribed the wrong word as the contact
        // name) and can BACK out rather than confirming a doomed
        // action.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.SendMessage, "app" to "SMS", "contact" to "dadd", "body" to "hi"),
        )
        assertEquals("action: text\ndetails: [NO_CONTACT] [dadd] hi", s)
    }

    @Test fun sendMessageWithSmsMissingContactShowsNoNameSentinel() {
        // No contact slot AND no resolvedContact slot — render the
        // `[NO_NAME]` sentinel (distinct from `[NO_CONTACT]`) so the
        // user knows the slot extractor pulled out nothing to look up,
        // rather than that the lookup itself failed.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.SendMessage, "app" to "SMS", "body" to "hi"),
        )
        assertEquals("action: text\ndetails: [NO_NAME] hi", s)
    }

    // ── Call ──────────────────────────────────────────────────────────────

    @Test fun call() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Call, "contact" to "alex", "resolvedContact" to ContactsRepository.Contact("alex", "555-0100")),
        )
        assertEquals("action: call\ndetails: [alex]", s)
    }

    @Test fun callWithResolvedFullNameShowsResolved() {
        // Spoken "alex" resolves to "Alex Smith" in Contacts — the
        // confirm prompt shows the resolved display name so the user
        // sees who they're actually about to dial.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Call, "contact" to "alex", "resolvedContact" to ContactsRepository.Contact("Alex Smith", "555-0100")),
        )
        assertEquals("action: call\ndetails: [Alex Smith]", s)
    }

    @Test fun callMissingContactShowsNoNameSentinel() {
        // No contact slot at all — the user said "call" with no name.
        // Render `[NO_NAME]` so they know nothing was extracted, vs
        // `[NO_CONTACT]` which means a name was tried + failed.
        val s = ConfirmSummaryBuilder.summary(result(Intent.Call))
        assertEquals("action: call\ndetails: [NO_NAME]", s)
    }

    @Test fun callWithUnresolvedContactEchoesSpokenAlongsideSentinel() {
        // Contact slot is populated (spoken="next") but resolvedContact
        // is missing — Contacts didn't find a match. The prompt should
        // show `[NO_CONTACT]` so the user knows dispatch will fail
        // AND `[next]` so they can see what string the lookup actually
        // used (i.e. catch the Whisper mishearing).
        val s = ConfirmSummaryBuilder.summary(result(Intent.Call, "contact" to "next"))
        assertEquals("action: call\ndetails: [NO_CONTACT] [next]", s)
    }

    // ── Reminder / CreateEvent (title + time) ─────────────────────────────

    @Test fun reminderWithTitleAndTime() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reminder, "title" to "buy milk", "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reminder\ndetails: buy milk @ "))
    }

    @Test fun reminderWithoutTitleUsesDefault() {
        // ReminderHandler defaults to no-fixed-title-but-fails-on-no-time.
        // Confirm body uses a generic placeholder so it reads as something
        // rather than "?".
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reminder, "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reminder\ndetails: Reminder @ "))
    }

    @Test fun reminderWithoutTime() {
        // Time slot missing — handler will fail with "Couldn't understand
        // the time". The confirm at least surfaces the title so the user
        // knows what was misheard.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reminder, "title" to "gym"),
        )
        assertEquals("action: reminder\ndetails: gym", s)
    }

    @Test fun createEventDefaultsTitleToMeeting() {
        // CreateEventHandler defaults title to "Meeting" when no "called X"
        // clause is present. Confirm must mirror that so it doesn't show "?".
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.CreateEvent, "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: schedule\ndetails: Meeting @ "))
    }

    @Test fun createEventUsesScheduleVerb() {
        // The action label is "schedule", not "create-event" — matches the
        // verb the user said and reads naturally on the watch.
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.CreateEvent, "title" to "Standup", "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: schedule\n"))
        assertTrue(s.contains("details: Standup @"))
    }

    // ── Cancel / Reschedule ───────────────────────────────────────────────

    @Test fun cancelWithoutTargetSaysLatestReminder() {
        // Bare cancels carry no target slot — the handler cancels the
        // most-recent pin. Confirm must spell that out so the user
        // doesn't see "?" and think nothing's selected.
        val s = ConfirmSummaryBuilder.summary(result(Intent.Cancel))
        assertEquals("action: cancel\ndetails: latest reminder", s)
    }

    @Test fun cancelWithNamedTarget() {
        val s = ConfirmSummaryBuilder.summary(result(Intent.Cancel, "target" to "gym"))
        assertEquals("action: cancel\ndetails: gym", s)
    }

    @Test fun rescheduleTargetAndTime() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reschedule, "target" to "gym", "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reschedule\ndetails: gym → "))
    }

    @Test fun rescheduleWithoutTargetUsesLatest() {
        val s = ConfirmSummaryBuilder.summary(
            result(Intent.Reschedule, "time" to fixedDate()),
        )
        assertTrue(s.startsWith("action: reschedule\ndetails: latest reminder → "))
    }

    // ── SetTimer ──────────────────────────────────────────────────────────

    @Test fun setTimerShowsCompactDuration() {
        assertEquals("action: timer\ndetails: 10m", ConfirmSummaryBuilder.summary(result(Intent.SetTimer, "seconds" to 600)))
    }

    @Test fun setTimerCompoundDuration() {
        assertEquals("action: timer\ndetails: 1m 30s", ConfirmSummaryBuilder.summary(result(Intent.SetTimer, "seconds" to 90)))
    }

    @Test fun setTimerSecondsOnly() {
        assertEquals("action: timer\ndetails: 45s", ConfirmSummaryBuilder.summary(result(Intent.SetTimer, "seconds" to 45)))
    }

    // ── Open / Play ───────────────────────────────────────────────────────

    @Test fun openApp() {
        assertEquals(
            "action: open\ndetails: spotify",
            ConfirmSummaryBuilder.summary(result(Intent.OpenApp, "app" to "spotify")),
        )
    }

    @Test fun mediaPlayWithApp() {
        assertEquals(
            "action: play\ndetails: audible",
            ConfirmSummaryBuilder.summary(result(Intent.MediaPlay, "app" to "audible")),
        )
    }

    // ── Read-only intents (still rendered for the preview log) ────────────

    @Test fun readOnlyIntentsRenderWithDash() {
        // Even though these never gate-on-confirm, the same builder is used
        // for the Phase A1.5 debug log. They should render cleanly, not "?".
        assertEquals("action: time\ndetails: -", ConfirmSummaryBuilder.summary(result(Intent.Time)))
        assertEquals("action: battery\ndetails: -", ConfirmSummaryBuilder.summary(result(Intent.Battery)))
        assertEquals("action: find-phone\ndetails: -", ConfirmSummaryBuilder.summary(result(Intent.FindPhone)))
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
        assertEquals("action: unknown\ndetails: an audiobook.", s)
    }

    // ── SendMessage (Phase A1+A2) ─────────────────────────────────────────

    @Test fun sendMessageWithWhatsApp() {
        val s = ConfirmSummaryBuilder.summary(
            result(
                Intent.SendMessage,
                "app" to "WhatsApp",
                "contact" to "mom",
                "resolvedContact" to ContactsRepository.Contact("mom", "555-0100"),
                "body" to "on my way",
            ),
        )
        assertEquals("action: whatsapp\ndetails: [mom] on my way", s)
    }

    @Test fun sendMessageWithSmsAppRendersAsText() {
        // Phase A2 routed bare-verb SMS through SendMessage with
        // app="SMS". The prompt should still read "action: text" — the
        // verb the user said — not "action: sms".
        val s = ConfirmSummaryBuilder.summary(
            result(
                Intent.SendMessage,
                "app" to "SMS",
                "contact" to "mom",
                "resolvedContact" to ContactsRepository.Contact("mom", "555-0100"),
                "body" to "hi",
            ),
        )
        assertEquals("action: text\ndetails: [mom] hi", s)
    }

    @Test fun sendMessageWithoutBody() {
        val s = ConfirmSummaryBuilder.summary(
            result(
                Intent.SendMessage,
                "app" to "WhatsApp",
                "contact" to "mom",
                "resolvedContact" to ContactsRepository.Contact("mom", "555-0100"),
            ),
        )
        assertEquals("action: whatsapp\ndetails: [mom]", s)
    }

    @Test fun sendMessageWithoutAppFallsBackToMessage() {
        // Defensive fallback when the slot extractor didn't populate
        // app — render as a generic "message" verb so the user still
        // gets a comprehensible prompt.
        val s = ConfirmSummaryBuilder.summary(
            result(
                Intent.SendMessage,
                "contact" to "mom",
                "resolvedContact" to ContactsRepository.Contact("mom", "555-0100"),
                "body" to "hi",
            ),
        )
        assertEquals("action: message\ndetails: [mom] hi", s)
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
