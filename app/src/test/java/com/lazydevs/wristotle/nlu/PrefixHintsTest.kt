// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.PrefixHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefixHintsTest {

    @Test fun `text prefix maps to SendMessage`() {
        // Phase A2 subsumed Intent.Sms into Intent.SendMessage. The
        // bare-verb shape ("text …" / "message …") still routes here;
        // SMS is the default delivery target inside SendMessageHandler
        // when no app name is parsed out of the body.
        assertEquals(Intent.SendMessage, PrefixHints.hintFor("text John saying yes"))
    }

    @Test fun `send a message to maps to SendMessage`() {
        assertEquals(Intent.SendMessage, PrefixHints.hintFor("send a message to dad"))
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
        assertNull(PrefixHints.hintFor("tell me a joke"))
    }

    @Test fun `leading whitespace doesn't block detection`() {
        assertEquals(Intent.Call, PrefixHints.hintFor("   call mom"))
    }

    @Test fun `case-insensitive matching`() {
        assertEquals(Intent.SendMessage, PrefixHints.hintFor("Text John"))
        assertEquals(Intent.SendMessage, PrefixHints.hintFor("TEXT JOHN"))
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

    @Test fun `sms with notes in body stays SendMessage`() {
        // Phase A2: "text mom …" routes to SendMessage (SMS as default
        // target). The cross-contamination guarantee still holds — the
        // Note rule loses to a leading verb opener.
        assertEquals(Intent.SendMessage, PrefixHints.hintFor("text mom notes look good"))
        assertEquals(Intent.SendMessage, PrefixHints.hintFor("message bob notes about today"))
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

    // --- CancelAlarm + Timer prefix hints (sit above Reminder / Cancel) ----------

    @Test fun `cancel alarm maps to CancelAlarm`() {
        assertEquals(Intent.CancelAlarm, PrefixHints.hintFor("cancel the alarm"))
    }

    @Test fun `stop the alarm maps to CancelAlarm`() {
        assertEquals(Intent.CancelAlarm, PrefixHints.hintFor("stop the alarm"))
    }

    @Test fun `time-qualified cancel maps to CancelAlarm`() {
        assertEquals(Intent.CancelAlarm, PrefixHints.hintFor("cancel the 7am alarm"))
    }

    @Test fun `set a timer maps to SetTimer`() {
        assertEquals(Intent.SetTimer, PrefixHints.hintFor("set a timer for 10 minutes"))
    }

    @Test fun `timer for maps to SetTimer`() {
        assertEquals(Intent.SetTimer, PrefixHints.hintFor("timer for 5 minutes"))
    }

    // --- WorldTime: prefix hint + refineWorldTime correction ------------

    @Test fun `time in a city maps to WorldTime via prefix hint`() {
        assertEquals(Intent.WorldTime, PrefixHints.hintFor("what time is it in tokyo"))
        assertEquals(Intent.WorldTime, PrefixHints.hintFor("time in london"))
    }

    @Test fun `bare time query is not a WorldTime hint`() {
        // No location → no WorldTime hint (and the watch answers it locally
        // anyway, so it never reaches the classifier path here).
        assertNull(PrefixHints.hintFor("what time is it"))
    }

    @Test fun `timer query is not a WorldTime hint`() {
        // \btime\b is whole-word, so "timer" must not trip the WorldTime rule.
        assertEquals(Intent.SetTimer, PrefixHints.hintFor("set a timer for 10 minutes in the kitchen"))
    }

    @Test fun `refineWorldTime upgrades a confident Time pick`() {
        // There's no companion Time handler; "what time is it in Tokyo" that
        // the classifier confidently labels Time must be rerouted.
        assertEquals(
            Intent.WorldTime,
            PrefixHints.refineWorldTime("what time is it in tokyo", Intent.Time),
        )
    }

    @Test fun `refineWorldTime upgrades an Unknown pick`() {
        assertEquals(
            Intent.WorldTime,
            PrefixHints.refineWorldTime("the time in paris", Intent.Unknown),
        )
    }

    @Test fun `refineWorldTime leaves a bare Time pick alone`() {
        assertEquals(
            Intent.Time,
            PrefixHints.refineWorldTime("what time is it", Intent.Time),
        )
    }

    @Test fun `refineWorldTime is a no-op for other intents`() {
        // A Calendar query that happens to contain "time ... in ..." keeps
        // its pick — refineWorldTime only ever touches Time / Unknown.
        assertEquals(
            Intent.Calendar,
            PrefixHints.refineWorldTime("what time is my meeting in the morning", Intent.Calendar),
        )
    }

    // --- Calculate: math-pattern hint -----------------------------------

    @Test fun `percent of maps to Calculate`() {
        assertEquals(Intent.Calculate, PrefixHints.hintFor("what's 15% of 80"))
        assertEquals(Intent.Calculate, PrefixHints.hintFor("how much is 30 percent of 200"))
    }

    @Test fun `arithmetic operators map to Calculate`() {
        assertEquals(Intent.Calculate, PrefixHints.hintFor("what's 25 plus 17"))
        assertEquals(Intent.Calculate, PrefixHints.hintFor("96 divided by 4"))
        assertEquals(Intent.Calculate, PrefixHints.hintFor("12 times 8"))
    }

    @Test fun `a reminder with a duration is not Calculate`() {
        // "20 minutes" is a number + time unit, not a number + operator.
        assertEquals(Intent.Reminder, PrefixHints.hintFor("remind me in 20 minutes to stretch"))
    }

    @Test fun `a timer is not Calculate`() {
        assertEquals(Intent.SetTimer, PrefixHints.hintFor("set a timer for 10 minutes"))
    }

    // --- Ordering anti-rules --------------------------------------------
    //
    // Each test below pins a precedence relationship — moving the
    // referenced rule will make the corresponding test fail. Add a row to
    // the "Ordering" table in PrefixHints' KDoc when you add a regression
    // case here.

    @Test fun `seek above bare Previous - back ten seconds is a seek`() {
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("back ten seconds"))
        assertEquals(Intent.MediaSeekBackward, PrefixHints.hintFor("go back 30 seconds"))
    }

    @Test fun `ListReminders above Reminder - list my reminders does not fire Reminder`() {
        // If the bare-verb Reminder rule moved above ListReminders, this
        // would mis-route as a fresh reminder creation.
        assertEquals(Intent.ListReminders, PrefixHints.hintFor("list my reminders"))
    }

    @Test fun `DeleteTask above Cancel - delete task X is DeleteTask not Cancel`() {
        // "delete X" alone routes to Cancel (reminder cancellation),
        // but with the explicit task noun it must route to DeleteTask.
        assertEquals(Intent.DeleteTask, PrefixHints.hintFor("delete the task buy milk"))
        assertEquals(Intent.DeleteTask, PrefixHints.hintFor("remove task call dentist"))
    }

    @Test fun `Reschedule above Reminder`() {
        assertEquals(
            Intent.Reschedule,
            PrefixHints.hintFor("reschedule my dentist appointment to 4pm"),
        )
    }

    @Test fun `morning brief maps to MorningBrief`() {
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("morning brief"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("brief me"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("brief me on today"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("daily summary"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("summary of today"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("summary of my day"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("today's rundown"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("rundown of today"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("what's my day"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("what's on my plate today"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("what's coming up today"))
        assertEquals(Intent.MorningBrief, PrefixHints.hintFor("what do i have today"))
    }

    @Test fun `bare good morning does NOT map to MorningBrief`() {
        // Greeting must NOT trigger the aggregator. The classifier's
        // fallback (Unknown) is the correct destination — the user
        // would have to be explicit.
        assertEquals(null, PrefixHints.hintFor("good morning"))
        assertEquals(null, PrefixHints.hintFor("morning"))
    }

    @Test fun `explicit tasks queries still route to ListTasks`() {
        // The MorningBrief rule must not regress the existing
        // "list my tasks" / "what are my tasks" routing — those have
        // the `tasks` keyword and stay with ListTasks.
        assertEquals(Intent.ListTasks, PrefixHints.hintFor("list my tasks"))
        assertEquals(Intent.ListTasks, PrefixHints.hintFor("what are my tasks"))
    }

    @Test fun `sport queries route to SportScore`() {
        // A team name can sit between the verb and the sports qualifier.
        assertEquals(Intent.SportScore, PrefixHints.hintFor("when is the next dodgers game"))
        assertEquals(Intent.SportScore, PrefixHints.hintFor("next warriors game"))
        assertEquals(Intent.SportScore, PrefixHints.hintFor("when do the lakers play"))
        assertEquals(Intent.SportScore, PrefixHints.hintFor("did the dodgers win"))
        assertEquals(Intent.SportScore, PrefixHints.hintFor("premier league table"))
        assertEquals(Intent.SportScore, PrefixHints.hintFor("nba standings"))
        assertEquals(Intent.SportScore, PrefixHints.hintFor("what's the score of the lakers game"))
    }

    @Test fun `sport rule does not steal media commands`() {
        // Bare "play <x>" / "next <x>" lack a sports qualifier → not SportScore.
        assertNotEquals(Intent.SportScore, PrefixHints.hintFor("play taylor swift"))
        assertNotEquals(Intent.SportScore, PrefixHints.hintFor("next song"))
    }
}