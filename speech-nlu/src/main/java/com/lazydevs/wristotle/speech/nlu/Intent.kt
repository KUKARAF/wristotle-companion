package com.lazydevs.wristotle.speech.nlu

/**
 * Discrete intents the NLU layer can route to. Each value corresponds to a
 * single [com.lazydevs.wristotle.handlers.ActionHandler] in the app module.
 *
 * Keep this enum stable — values are persisted by name (not ordinal) into
 * the example bank and conversation history, so reordering is safe but
 * renames require a Room migration.
 */
enum class Intent {
    Call,
    Sms,
    Reminder,
    Cancel,

    /** Read-only: list the pending reminders the user has set ("what are my
     *  reminders", "list my reminders"). No slots. Handled by
     *  ListRemindersHandler reading the local PinStore. */
    ListReminders,

    /** Move an existing reminder to a new time ("snooze", "push it to 6pm",
     *  "reschedule my gym reminder to noon"). Slots: `time` (java.util.Date,
     *  required) and `target` (String?, which reminder). Handled by
     *  RescheduleHandler: delete the old pin, re-insert at the new time. */
    Reschedule,

    FindPhone,
    Time,
    Battery,
    Steps,
    Vibrate,

    /** Resume / start playback on the active media app (Spotify, YouTube, …). */
    MediaPlay,
    /** Pause the active media app. */
    MediaPause,
    /** Toggle play / pause — the handler reads current state to decide.
     *  Useful when the classifier is uncertain between play and pause. */
    MediaPlayPause,
    /** Skip to the next track / episode. */
    MediaNext,
    /** Skip to the previous track / episode. */
    MediaPrevious,
    /** Seek forward within the current track. Slot: `seconds: Int` (default 30). */
    MediaSeekForward,
    /** Seek backward within the current track. Slot: `seconds: Int` (default 10). */
    MediaSeekBackward,

    /** Launch an installed app by spoken name ("open Spotify", "launch
     *  Audible"). Slot: `app: String` (raw name as the user said it) —
     *  the handler resolves it to a package id via the AppIndex. */
    OpenApp,

    /** Read-only calendar queries — "when is my next meeting", "next 3
     *  meetings", "do I have anything on May 25". Slots: `count` (Int,
     *  default 1) and `date` (java.util.Date?, set when the query names a
     *  day). Handled by CalendarHandler reading CalendarContract. */
    Calendar,

    /** Capture a free-form text note ("note: pick up milk", "note that the
     *  meeting moved to 4", "remember that the cat's vet is on May 30").
     *  Slot: `body` (String) — the note text with the lead-in stripped.
     *  Handled by NoteHandler persisting to a Room store; on microPebble
     *  the dictation .wav is copied into a per-note audio file. */
    Note,

    /** Append to the most recently created note ("add to my previous note …",
     *  "add to the last note …", "append …"). Slot: `body` (String).
     *  Handled by AppendNoteHandler — updates the existing row's body and
     *  bumps its timestamp so it surfaces at the top of the list. */
    AppendNote,

    /** Create a calendar event — "schedule a meeting tomorrow at 3pm",
     *  "add a meeting with Alex Friday at noon called standup". Slots:
     *  `time` (java.util.Date, required), `title` (String?), `attendee`
     *  (String?), `durationMinutes` (Int, default 60). Handled by
     *  CreateEventHandler writing to CalendarContract (WRITE_CALENDAR). */
    CreateEvent,

    /** Open a third-party messaging app's compose screen with the contact
     *  and body pre-filled — "WhatsApp Mom on my way", "Telegram Dad I'll
     *  be late", "send a Signal message to Alex about meeting moved".
     *  Slots: `app` (String — display name of the target app),
     *  `contact` (String — recipient name), `body` (String — message text).
     *  Handled by SendMessageHandler: resolves contact via ContactsRepository,
     *  looks up the app in MessagingTargets, fires ACTION_VIEW / ACTION_SEND
     *  with the pre-filled body so the user only has to tap Send.
     *
     *  Distinct from [Sms] which sends programmatically via SmsManager and
     *  doesn't require a tap; the messaging apps gatekeep their playback /
     *  send APIs to Google-Assistant-signed callers, so assisted-send via
     *  intent is the only sideloaded path. Phase A2 will subsume Sms into
     *  this intent with the SMS app as one entry in the registry. */
    SendMessage,

    /** Fallback when no other intent matches with sufficient confidence. */
    Unknown,
    ;

    companion object {
        /** Case-insensitive lookup by name; returns [Unknown] if not found. */
        fun fromName(name: String): Intent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}
