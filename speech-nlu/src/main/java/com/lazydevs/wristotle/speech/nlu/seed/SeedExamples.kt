package com.lazydevs.wristotle.speech.nlu.seed

import com.lazydevs.wristotle.speech.nlu.Intent

/**
 * Initial labeled examples bundled with the app. Embedded at startup to
 * seed the [com.lazydevs.wristotle.speech.nlu.embedding.EmbeddingIntentClassifier]
 * centroids; learned examples (added via [com.lazydevs.wristotle.speech.nlu.bank.LearningCollector])
 * accumulate alongside.
 *
 * Phrasings target the variation we expect from voice input:
 *   - canonical: "call mom", "text bob hi"
 *   - paraphrases: "ring mom", "tell dad I'm running late"
 *   - filler / conversational: "could you call my mom please"
 *   - synonyms across the contact-action verbs (call/dial/ring/phone)
 *
 * Each list should stay at ~15-25 examples — enough to define a centroid
 * with semantic variety but small enough that startup embedding cost
 * stays under ~500 ms (the whole bank is ~150 embeddings = ~50 ms at
 * warm-cache MiniLM).
 */
object SeedExamples {

    val all: List<Pair<Intent, String>> = buildList {
        // ── Call ────────────────────────────────────────────────────────
        addAll(Intent.Call, listOf(
            "call mom",
            "call dad",
            "dial john",
            "phone lisa",
            "ring mom",
            "give mom a call",
            "give dad a ring",
            "could you call my mom please",
            "would you ring lisa",
            "place a call to john",
            "i want to call dad",
            "let's call mom",
            "phone home",
            "dial my dentist",
            "ring up my brother",
            "call my office",
        ))
        // ── SendMessage ─────────────────────────────────────────────────
        // Phase A2 unified SMS into SendMessage. Both shapes train the
        // same centroid because the slot extractor differentiates at
        // dispatch time (no app named → SmsTarget; app named →
        // WhatsApp / Telegram / Signal).
        //
        // - "Bare" rows (text mom hi / send sms to lisa) target SMS
        //   via programmatic SmsManager.sendTextMessage. No tap.
        // - "App-named" rows (WhatsApp / Telegram / Signal …) target
        //   the third-party messaging apps via assisted-send (opens
        //   compose pre-filled, user taps Send).
        //
        // Order is mixed deliberately — keeping bare + app-named
        // phrasings interleaved means the centroid generalises across
        // delivery shapes without leaning toward one. The classifier
        // doesn't see slots; only text.
        addAll(Intent.SendMessage, listOf(
            // Bare SMS-style phrasings (formerly Intent.Sms seeds).
            "text mom hi",
            "text dad I'll be home soon",
            "message lisa about dinner",
            "send a message to john saying running late",
            "tell mom I'm on my way",
            "tell dad the meeting moved to three",
            "send sms to lisa",
            "text my brother happy birthday",
            "let mom know I'm coming",
            "shoot dad a text",
            "message my wife I love you",
            "send a quick message to the office",
            "tell my boss I'll be late",
            "ping lisa",
            "text bob that I'll be there in five",
            // App-named phrasings (third-party messaging apps).
            "WhatsApp mom on my way",
            "WhatsApp dad I'll be late",
            "WhatsApp lisa happy birthday",
            "Telegram alex meeting moved to five",
            "Telegram my brother see you soon",
            "Signal bob meeting at three",
            "send a WhatsApp message to mom on my way",
            "send a Telegram message to dad",
            "send a Signal message to alex about the meeting",
            "text mom on WhatsApp on my way",
            "message bob on Telegram I'll be there in five",
            "WhatsApp the office I'll be late",
            "Telegram mom happy birthday",
            "Signal dad about the meeting",
            "shoot lisa a WhatsApp",
        ))
        // ── Reminder ────────────────────────────────────────────────────
        addAll(Intent.Reminder, listOf(
            "remind me to pick up milk at five pm",
            "set a reminder for the meeting tomorrow at noon",
            "remember to call the dentist on friday",
            "set an alarm for ten am",
            "wake me up at seven",
            "remind me to take my pills at nine",
            "schedule a reminder for laundry tonight",
            "ping me in twenty minutes",
            "buzz me at three pm",
            "remind me about the standup at nine thirty",
            "set timer for fifteen minutes",
            "remind me to leave by six",
            "I need a reminder to call grandma tomorrow",
            "tell me when it's three pm",
            "remind me later",
        ))
        // ── Cancel ──────────────────────────────────────────────────────
        addAll(Intent.Cancel, listOf(
            "cancel that reminder",
            "cancel my last reminder",
            "remove the reminder",
            "delete the alarm I just set",
            "scratch that reminder",
            "never mind the reminder",
            "cancel it",
            "forget that reminder",
            "kill the alarm",
            "drop the reminder",
            "undo that reminder",
            "scrap the last reminder",
            "cancel my gym reminder",
            "cancel the dentist reminder",
            "delete my reminder about the meeting",
            "cancel my five pm reminder",
            "remove the reminder to call mom",
        ))
        // ── ListReminders ───────────────────────────────────────────────
        addAll(Intent.ListReminders, listOf(
            "what are my reminders",
            "list my reminders",
            "show my reminders",
            "what reminders do I have",
            "do I have any reminders",
            "read my reminders",
            "what have I set",
            "what's on my reminder list",
            "tell me my reminders",
            "any reminders",
            "show me what I need to do",
            "what am I supposed to remember",
            // Query forms that name a time — must NOT be heard as "create a
            // reminder at <time>". The leading "is there / do I have / any"
            // is the signal; the time is incidental.
            "is there a reminder at two pm",
            "do I have a reminder at five",
            "is there anything at three pm",
            "any reminders at noon",
            "do I have a reminder for two pm",
            "have I got a reminder at four",
            "is there a reminder later today",
        ))
        // ── Reschedule ──────────────────────────────────────────────────
        addAll(Intent.Reschedule, listOf(
            "snooze my reminder for ten minutes",
            "snooze for five minutes",
            "reschedule my reminder to six pm",
            "push my reminder to seven",
            "move my gym reminder to noon",
            "postpone the dentist reminder to tomorrow",
            "delay my reminder by an hour",
            "push it back to eight pm",
            "move my reminder to later",
            "reschedule the meeting reminder for three",
            "bump my reminder to nine",
            "shift my reminder to the morning",
        ))
        // ── FindPhone ───────────────────────────────────────────────────
        addAll(Intent.FindPhone, listOf(
            "find my phone",
            "where is my phone",
            "ping my phone",
            "ring my phone",
            "make my phone ring",
            "I lost my phone",
            "can't find my phone",
            "help me locate my phone",
            "play a sound on my phone",
            "where did I leave my phone",
            "phone finder",
            "find the phone",
        ))
        // ── Time ────────────────────────────────────────────────────────
        addAll(Intent.Time, listOf(
            "what time is it",
            "tell me the time",
            "what's the time",
            "current time",
            "do you know what time it is",
            "what time",
            "time please",
            "clock",
            "show me the time",
            "give me the time",
        ))
        // ── Battery ─────────────────────────────────────────────────────
        addAll(Intent.Battery, listOf(
            "what's my battery",
            "battery level",
            "how much battery do I have",
            "battery percentage",
            "what's the battery at",
            "charge level",
            "how charged am I",
            "battery status",
            "show battery",
            "is my battery low",
        ))
        // ── Steps ───────────────────────────────────────────────────────
        addAll(Intent.Steps, listOf(
            "how many steps today",
            "step count",
            "what's my step count",
            "show me my steps",
            "steps today",
            "how many steps have I taken",
            "show my activity",
            "how active have I been",
            "walking total",
            "today's steps",
        ))
        // ── Vibrate ─────────────────────────────────────────────────────
        addAll(Intent.Vibrate, listOf(
            "vibrate",
            "buzz me",
            "vibrate the watch",
            "give me a buzz",
            "test vibration",
            "shake",
            "buzz",
            "make the watch vibrate",
            "vibrate now",
        ))
        // ── MediaPlay ───────────────────────────────────────────────────
        addAll(Intent.MediaPlay, listOf(
            "play",
            "play music",
            "play the music",
            "resume",
            "resume playback",
            "start playing",
            "play the song",
            "play it",
            "start the music",
            "keep playing",
        ))
        // ── MediaPause ──────────────────────────────────────────────────
        addAll(Intent.MediaPause, listOf(
            "pause",
            "pause music",
            "pause the music",
            "pause playback",
            "pause the song",
            "hold the music",
            "halt the music",
            "stop the music",
            "stop playing",
        ))
        // ── MediaPlayPause ──────────────────────────────────────────────
        addAll(Intent.MediaPlayPause, listOf(
            "play pause",
            "toggle playback",
            "toggle the music",
            "toggle music",
        ))
        // ── MediaNext ───────────────────────────────────────────────────
        addAll(Intent.MediaNext, listOf(
            "next",
            "next song",
            "next track",
            "skip",
            "skip this",
            "skip the song",
            "skip to next",
            "play next",
            "next episode",
        ))
        // ── MediaPrevious ───────────────────────────────────────────────
        addAll(Intent.MediaPrevious, listOf(
            "previous",
            "previous song",
            "previous track",
            "back",
            "go back",
            "last song",
            "last track",
            "play previous",
            "previous episode",
        ))
        // ── MediaSeekForward ────────────────────────────────────────────
        addAll(Intent.MediaSeekForward, listOf(
            "skip ahead",
            "skip ahead thirty seconds",
            "skip forward",
            "skip forward fifteen seconds",
            "fast forward",
            "fast forward ten seconds",
            "jump forward",
            "jump ahead",
            "forward thirty seconds",
        ))
        // ── MediaSeekBackward ───────────────────────────────────────────
        addAll(Intent.MediaSeekBackward, listOf(
            "rewind",
            "rewind ten seconds",
            "rewind fifteen seconds",
            "skip back",
            "skip backward",
            "go back ten seconds",
            "back ten seconds",
            "back thirty seconds",
            "play that again",
        ))
        // ── OpenApp ─────────────────────────────────────────────────────
        // Verb-led patterns; the actual app name is open-vocabulary and
        // handled by the slot extractor + AppIndex lookup at dispatch
        // time, so the seeds focus on teaching the classifier the verb
        // shapes rather than every possible app name.
        addAll(Intent.OpenApp, listOf(
            "open spotify",
            "open youtube",
            "open chrome",
            "open the calculator",
            "launch maps",
            "launch the camera",
            "start gmail",
            "start the browser",
            "fire up settings",
            "load instagram",
            "bring up calendar",
            "switch to messages",
            "go to the play store",
        ))
        // ── Calendar ────────────────────────────────────────────────────
        // Read-only calendar lookups. Open-vocabulary dates/counts are
        // handled by CalendarSlots at dispatch time; seeds teach the
        // classifier the question shapes (next meeting / count / specific
        // day / general "what's on…").
        addAll(Intent.Calendar, listOf(
            "when is my next meeting",
            "what's my next meeting",
            "when's my next appointment",
            "what are my next three meetings",
            "what are my next two meetings",
            "show me my next meetings",
            "do I have any meetings today",
            "what's on my calendar today",
            "what's on my calendar tomorrow",
            "do I have a meeting on may twenty fifth",
            "do I have anything on friday",
            "what meetings do I have tomorrow",
            "am I free this afternoon",
            "what's on my schedule",
            "do I have any appointments next monday",
            "when is my next event",
        ))
        // ── CreateEvent ─────────────────────────────────────────────────
        // Write side of the calendar — lean on action verbs (schedule /
        // create / add / book / set up) + a calendar noun so it stays
        // distinct from the read-only Calendar queries above and from
        // Reminder ("set a reminder for the meeting").
        addAll(Intent.CreateEvent, listOf(
            "schedule a meeting tomorrow at three pm",
            "create a meeting with Alex on friday at noon",
            "add a meeting on may twenty fifth at ten am",
            "set up a meeting monday at nine",
            "book an appointment tomorrow afternoon",
            "new event friday at two pm called standup",
            "put a meeting on my calendar tomorrow at four",
            "add a one hour meeting tomorrow at eleven",
            "schedule a call with the team next monday at ten",
            "create an appointment with the dentist on thursday at three",
            "make a meeting for tomorrow morning",
            "add an event saturday at six pm",
            "schedule a one on one with Sam tomorrow at one",
            "book a meeting room friday at noon",
        ))
        // ── Note ────────────────────────────────────────────────────────
        // Free-form capture. Lead-ins ("note", "make a note", "remember
        // that", "jot down") are deterministic in PrefixHints, so seeds
        // here mostly cover the paraphrases without an explicit lead-in
        // verb where the classifier has to lean on the body shape.
        addAll(Intent.Note, listOf(
            "note pick up milk on the way home",
            "note that the meeting moved to four pm",
            "make a note to buy batteries",
            "make a note that the cat's vet visit is on may thirtieth",
            "remember that the wifi password changed",
            "remember to water the plants",
            "jot down the parking spot is b twelve",
            "save a note about the new gym schedule",
            "write down the door code is one two three four",
            "take a note the rental car return is at noon",
            "noted that the recipe needs more salt",
            "add to my notes the conference room is on the third floor",
            "for my notes the new account number is on the desk",
            "keep a note that the contractor said tuesday",
            "store a note about the next book club pick",
        ))
        // ── AppendNote ──────────────────────────────────────────────────
        // "Add this to the previous one" pattern. PrefixHints catches the
        // canonical openers; seeds cover the variations.
        addAll(Intent.AppendNote, listOf(
            "add to my previous note the meeting is now at five",
            "add to my previous notes that the room changed",
            "add to the last note the dentist office is on 5th street",
            "add to the latest note alex is bringing snacks",
            "append the speaker is bob",
            "append to my note the new tracking number is twelve thirty four",
            "append to the previous note we need extra chairs",
            "append to last note the time is now four pm",
            "also add to my notes the conference room is on the third floor",
            "also note in my last entry that the recipe needs more salt",
        ))
        // ── AddTask ─────────────────────────────────────────────────────
        // Narrow trigger words: explicit "task" or "to-do" noun. Avoid
        // overlap with Note ("remember", "jot"), Reminder ("remind",
        // "set a reminder"), and CreateEvent ("schedule"). The PrefixHints
        // anti-rule + the ordering above keeps the boundary deterministic
        // for the cases that matter; the seeds here teach the classifier
        // the centroid.
        addAll(Intent.AddTask, listOf(
            "add task buy milk",
            "add task pick up the package",
            "add task call the dentist",
            "new task fix the kitchen sink",
            "new task book a haircut",
            "create a task review the budget",
            "create task plan the weekend trip",
            "make a task email the team",
            "task drop off the dry cleaning",
            "task return the library books",
            "add buy milk to my tasks",
            "add pick up package to my tasks",
            "add fix the sink to my to-do list",
            "put email the team on my tasks",
            "put grocery shopping on my todo",
            // "Leading-noun" shape — Whisper sometimes inverts the body
            // and the trailing noun. *"add task to buy milk"* comes back
            // as *"add to my tasks buy milk"*. Teach the centroid both
            // orders so the classifier doesn't drift toward ListTasks
            // when this shape appears.
            "add to my tasks buy oysters",
            "add to my tasks pick up the package",
            "add to my to-do list call the dentist",
            "put on my tasks email the team",
        ))
        // ── ListTasks ───────────────────────────────────────────────────
        // Interrogatives + list / show + the `tasks` noun. Mirrors the
        // ListReminders seed shape — query forms that are read-only by
        // construction.
        addAll(Intent.ListTasks, listOf(
            "what are my tasks",
            "list my tasks",
            "show my tasks",
            "show me my tasks",
            "what tasks do I have",
            "do I have any tasks",
            "any tasks",
            "what's on my task list",
            "read my tasks",
            "tell me my tasks",
            "what's left on my to-do list",
            "what's on my todo",
            "any open tasks",
            "what do I need to do",
            "what tasks are still pending",
        ))
        // ── CompleteTask ────────────────────────────────────────────────
        // Task-specific verbs — "complete", "mark done", "finish",
        // "done with", "tick off". The `target` slot extracted by
        // CompleteTaskSlots is what the handler matches against pending
        // tasks; seeds train the classifier on the verb shapes only.
        addAll(Intent.CompleteTask, listOf(
            "complete buy milk",
            "complete call the dentist",
            "complete pick up the package",
            "mark buy milk done",
            "mark call the dentist as done",
            "mark the laundry as complete",
            "finish buy groceries",
            "finished buy bread",
            "done with the laundry",
            "done with the gym task",
            "tick off buy milk",
            "check off call the dentist",
            "complete the last task",
            "mark the latest task done",
            "complete my most recent task",
        ))
        // ── DeleteTask ──────────────────────────────────────────────────
        // Same target-extraction logic as CompleteTask but the verb is
        // delete/remove/scratch. Seeds emphasise the task-context
        // anchor ("from my tasks", "task X") so the centroid sits
        // clearly away from Cancel (reminder cancellation).
        addAll(Intent.DeleteTask, listOf(
            "delete task buy milk",
            "delete the task buy milk",
            "delete buy milk from my tasks",
            "remove task buy milk",
            "remove buy milk from my tasks",
            "remove call the dentist from my todo",
            "scratch buy milk off my list",
            "scratch the gym task off my todo",
            "drop the laundry task",
            "drop call the dentist from my tasks",
            "remove the last task",
            "delete my latest task",
            "remove my most recent task",
            "delete task pick up the package",
            "scratch task buy oysters",
        ))
        // Intent.Unknown intentionally has no seeds — it's the fallback
        // when nothing else clears the confidence threshold.
    }

    private fun MutableList<Pair<Intent, String>>.addAll(intent: Intent, phrases: List<String>) {
        phrases.forEach { add(intent to it) }
    }
}
