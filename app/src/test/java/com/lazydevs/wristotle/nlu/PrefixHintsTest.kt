package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.speech.nlu.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixHintsTest {

    @Test fun `text prefix maps to Sms`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("text John saying yes"))
    }

    @Test fun `send a message to maps to Sms`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("send a message to dad"))
    }

    @Test fun `call prefix maps to Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("call mom"))
    }

    @Test fun `dial prefix maps to Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("dial 911"))
    }

    @Test fun `ring prefix maps to Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("ring my mom"))
    }

    @Test fun `remind prefix maps to Reminder`() {
        assertEquals(Intent.Reminder, PrefixHints.hintFor("remind me at 5pm"))
    }

    // --- ListReminders query forms must beat the Reminder create rule ---

    @Test fun `is there a reminder at a time maps to ListReminders`() {
        assertEquals(Intent.ListReminders, PrefixHints.hintFor("is there a reminder at 2pm"))
    }

    @Test fun `is that a reminder mishearing still maps to ListReminders`() {
        // Whisper often hears "is there" as "is that".
        assertEquals(Intent.ListReminders, PrefixHints.hintFor("is that a reminder at 2 p.m."))
    }

    @Test fun `do I have a reminder maps to ListReminders`() {
        assertEquals(Intent.ListReminders, PrefixHints.hintFor("do I have a reminder at five"))
    }

    @Test fun `remainder mishearing maps to ListReminders`() {
        assertEquals(Intent.ListReminders, PrefixHints.hintFor("do I have a remainder at 10 pm"))
    }

    @Test fun `what are my reminders maps to ListReminders`() {
        assertEquals(Intent.ListReminders, PrefixHints.hintFor("what are my reminders"))
    }

    @Test fun `create reminder still maps to Reminder not List`() {
        assertEquals(Intent.Reminder, PrefixHints.hintFor("remind me to call mom at 2pm"))
        assertEquals(Intent.Reminder, PrefixHints.hintFor("set a reminder for 2pm"))
    }

    // --- CreateEvent: schedule/create + event noun ---

    @Test fun `schedule a meeting maps to CreateEvent`() {
        assertEquals(Intent.CreateEvent, PrefixHints.hintFor("schedule a meeting with bob at 3pm for 1 hour"))
    }

    @Test fun `set up and create event variants map to CreateEvent`() {
        assertEquals(Intent.CreateEvent, PrefixHints.hintFor("set up a meeting tomorrow"))
        assertEquals(Intent.CreateEvent, PrefixHints.hintFor("create an event for friday"))
        assertEquals(Intent.CreateEvent, PrefixHints.hintFor("book an appointment at noon"))
    }

    @Test fun `schedule a reminder does not map to CreateEvent`() {
        // No event noun → not CreateEvent; left to the reminder path.
        assertEquals(null, PrefixHints.hintFor("schedule a reminder for 5pm"))
    }

    // --- Reschedule openers beat the Reminder create rule ---

    @Test fun `push my reminder maps to Reschedule`() {
        assertEquals(Intent.Reschedule, PrefixHints.hintFor("push my reminder to 8pm"))
    }

    @Test fun `snooze and reschedule and move map to Reschedule`() {
        assertEquals(Intent.Reschedule, PrefixHints.hintFor("snooze my reminder for 10 minutes"))
        assertEquals(Intent.Reschedule, PrefixHints.hintFor("reschedule my dentist reminder to noon"))
        assertEquals(Intent.Reschedule, PrefixHints.hintFor("move my gym reminder to 6"))
    }

    @Test fun `remind me does not map to Reschedule`() {
        // Create still wins for a remind opener (no push/snooze/etc).
        assertEquals(Intent.Reminder, PrefixHints.hintFor("remind me to push the cart at 5pm"))
    }

    @Test fun `cancel prefix maps to Cancel`() {
        assertEquals(Intent.Cancel, PrefixHints.hintFor("cancel reminder"))
    }

    @Test fun `find phone variants map to FindPhone`() {
        assertEquals(Intent.FindPhone, PrefixHints.hintFor("find my phone"))
        assertEquals(Intent.FindPhone, PrefixHints.hintFor("where is my phone"))
        assertEquals(Intent.FindPhone, PrefixHints.hintFor("where's my phone"))
    }

    @Test fun `non-prefix occurrence is ignored`() {
        // "call" mid-sentence shouldn't trigger Call hint.
        assertNull(PrefixHints.hintFor("when should I call back"))
    }

    @Test fun `unknown phrase returns null`() {
        assertNull(PrefixHints.hintFor("what's the weather"))
    }

    @Test fun `leading whitespace doesn't block detection`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("   call mom"))
    }

    @Test fun `case-insensitive matching`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("Text John"))
        assertEquals(Intent.Sms, PrefixHints.hintFor("TEXT JOHN"))
    }

    // --- Media prefix rules ----------------------------------------

    @Test fun `play prefix maps to MediaPlay`() {
        assertEquals(Intent.MediaPlay, PrefixHints.hintFor("play"))
        assertEquals(Intent.MediaPlay, PrefixHints.hintFor("play spotify"))
        assertEquals(Intent.MediaPlay, PrefixHints.hintFor("resume"))
        assertEquals(Intent.MediaPlay, PrefixHints.hintFor("continue podcast"))
    }

    @Test fun `pause prefix maps to MediaPause`() {
        assertEquals(Intent.MediaPause, PrefixHints.hintFor("pause"))
        assertEquals(Intent.MediaPause, PrefixHints.hintFor("pause music"))
        assertEquals(Intent.MediaPause, PrefixHints.hintFor("halt"))
    }

    @Test fun `next and skip map to MediaNext`() {
        assertEquals(Intent.MediaNext, PrefixHints.hintFor("next"))
        assertEquals(Intent.MediaNext, PrefixHints.hintFor("next song"))
        assertEquals(Intent.MediaNext, PrefixHints.hintFor("skip"))
    }

    @Test fun `previous and last map to MediaPrevious`() {
        assertEquals(Intent.MediaPrevious, PrefixHints.hintFor("previous"))
        assertEquals(Intent.MediaPrevious, PrefixHints.hintFor("previous track"))
        assertEquals(Intent.MediaPrevious, PrefixHints.hintFor("last song"))
    }

    @Test fun `seek-forward variants map to MediaSeekForward`() {
        assertEquals(Intent.MediaSeekForward, PrefixHints.hintFor("skip ahead 30 seconds"))
        assertEquals(Intent.MediaSeekForward, PrefixHints.hintFor("fast forward"))
        assertEquals(Intent.MediaSeekForward, PrefixHints.hintFor("jump forward"))
        assertEquals(Intent.MediaSeekForward, PrefixHints.hintFor("forward 30 seconds"))
    }

    @Test fun `seek-backward variants map to MediaSeekBackward`() {
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("rewind"))
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("skip back"))
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("go back 10 seconds"))
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("back ten seconds"))
    }

    @Test fun `seek rules win over the broader next previous rules`() {
        // "skip ahead" must NOT be MediaNext just because it starts
        // with "skip". The seek rules are listed first in the
        // HINTS list specifically to claim these phrasings.
        assertEquals(Intent.MediaSeekForward, PrefixHints.hintFor("skip ahead 30"))
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("go back 30 seconds"))
    }

    // --- OpenApp prefix rules --------------------------------------

    @Test fun `open and launch and fire up map to OpenApp`() {
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("open spotify"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("launch the camera"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("fire up settings"))
    }

    @Test fun `bring up switch to go to all map to OpenApp`() {
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("bring up calendar"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("switch to messages"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("go to the play store"))
    }

    @Test fun `start run load only map to OpenApp when followed by a target`() {
        // The rule requires \S after the verb so a bare "start" doesn't
        // get captured (it's too ambiguous on its own).
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("start spotify"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("run chrome"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("load instagram"))
    }

    @Test fun `play with a body does not get captured by OpenApp rule`() {
        // OpenApp sits BELOW the MediaPlay rule in the HINTS list so
        // "play youtube" stays MediaPlay even though "play" isn't an
        // open-verb itself.
        assertEquals(Intent.MediaPlay, PrefixHints.hintFor("play youtube"))
    }

    // ─── Note + AppendNote rules ─────────────────────────────────────────
    // These tests pin the cross-contamination guarantees: any
    // "note(s)"-flavoured opener routes to Note, but a verb opener
    // (text/call/play/open/schedule/remind/etc.) wins above it.

    @Test fun `note colon routes to Note`() {
        assertEquals(Intent.Note, PrefixHints.hintFor("note: pick up milk"))
    }

    @Test fun `note with whisper punctuation routes to Note`() {
        // Whisper auto-punctuates with period and capital — the lead-in
        // tolerates [\s.:,;!?\-] right after note/notes.
        assertEquals(Intent.Note, PrefixHints.hintFor("Note. Pick up milk"))
        assertEquals(Intent.Note, PrefixHints.hintFor("Notes, room changed"))
    }

    @Test fun `notes plural routes to Note`() {
        assertEquals(Intent.Note, PrefixHints.hintFor("notes this is a test"))
    }

    @Test fun `make a note variants route to Note`() {
        assertEquals(Intent.Note, PrefixHints.hintFor("make a note to buy bread"))
        assertEquals(Intent.Note, PrefixHints.hintFor("take a note that meeting moved"))
        assertEquals(Intent.Note, PrefixHints.hintFor("save a note about the trip"))
    }

    @Test fun `jot and write down route to Note`() {
        assertEquals(Intent.Note, PrefixHints.hintFor("jot down the door code"))
        assertEquals(Intent.Note, PrefixHints.hintFor("write down the parking spot"))
        assertEquals(Intent.Note, PrefixHints.hintFor("jot this down: account number"))
    }

    @Test fun `remember that routes to Note but bare remember does not`() {
        // "remember that …" is unambiguous capture; "remember to …" without
        // a time is genuinely ambiguous (note vs reminder) so we leave it
        // to the classifier. Pinned so a future regex tweak doesn't drift.
        assertEquals(Intent.Note, PrefixHints.hintFor("remember that the wifi changed"))
        assertNull(PrefixHints.hintFor("remember to water the plants"))
    }

    @Test fun `noted routes to Note`() {
        assertEquals(Intent.Note, PrefixHints.hintFor("noted that the recipe needs salt"))
    }

    @Test fun `add to my notes still creates a new note (not append)`() {
        // "add to my notes" without previous/last/latest stays Note (create).
        // The AppendNote rule sits ABOVE Note and requires one of those
        // qualifiers; this asserts the boundary holds.
        assertEquals(Intent.Note, PrefixHints.hintFor("add to my notes the conference room is on the third floor"))
        assertEquals(Intent.Note, PrefixHints.hintFor("for my notes the new account number is on the desk"))
    }

    // ─── AppendNote rule ─────────────────────────────────────────────────

    @Test fun `add to previous notes routes to AppendNote`() {
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("add to previous notes the room changed"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("add to my previous note speaker is bob"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("add to the last note alex is bringing snacks"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("add to the latest notes meeting moved"))
    }

    @Test fun `append verb alone routes to AppendNote`() {
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("append the speaker is bob"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("append to my note tracking number"))
    }

    // Whisper mishears "append" as "amend" reliably enough that we accept
    // both spellings. "amend" is unambiguous (can only modify an existing
    // thing) so it routes to AppendNote without needing the "previous/last/
    // latest/recent" qualifier that disambiguates "add".
    @Test fun `amend verb routes to AppendNote`() {
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("amend the notes this is a test"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("amend to the notes: this is a test"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("amend door code is 9876"))
        assertEquals(Intent.AppendNote, PrefixHints.hintFor("amend to my last note speaker is bob"))
    }

    // ─── Cross-contamination: other verbs MUST win over Note ─────────────

    @Test fun `sms with notes in body stays Sms`() {
        assertEquals(Intent.Sms, PrefixHints.hintFor("text mom notes look good"))
        assertEquals(Intent.Sms, PrefixHints.hintFor("message bob notes about today"))
    }

    @Test fun `call with notes in body stays Call`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("call mom about notes"))
    }

    @Test fun `open notes app routes to OpenApp not Note`() {
        // The OpenApp rule sits above Note; "open notes …" stays OpenApp.
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("open notes app"))
        assertEquals(Intent.OpenApp, PrefixHints.hintFor("launch the notes app"))
    }

    @Test fun `play the notes podcast stays MediaPlay`() {
        assertEquals(Intent.MediaPlay, PrefixHints.hintFor("play the notes podcast"))
    }

    @Test fun `schedule a meeting about notes routes to CreateEvent`() {
        assertEquals(
            Intent.CreateEvent,
            PrefixHints.hintFor("schedule a meeting about notes tomorrow at 3pm"),
        )
    }

    @Test fun `remind me to send notes routes to Reminder`() {
        // Reminder rule wins on the "remind" opener; the watch-hint family
        // refinement would re-enforce this too.
        assertEquals(
            Intent.Reminder,
            PrefixHints.hintFor("remind me to send notes to bob at 5pm"),
        )
    }
}
