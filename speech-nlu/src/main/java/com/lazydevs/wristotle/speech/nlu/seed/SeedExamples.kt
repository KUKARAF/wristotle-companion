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
        // ── Sms ─────────────────────────────────────────────────────────
        addAll(Intent.Sms, listOf(
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
        // Intent.Unknown intentionally has no seeds — it's the fallback
        // when nothing else clears the confidence threshold.
    }

    private fun MutableList<Pair<Intent, String>>.addAll(intent: Intent, phrases: List<String>) {
        phrases.forEach { add(intent to it) }
    }
}
