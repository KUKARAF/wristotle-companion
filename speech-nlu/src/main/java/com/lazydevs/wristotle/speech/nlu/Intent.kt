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

    /** Create a calendar event — "schedule a meeting tomorrow at 3pm",
     *  "add a meeting with Alex Friday at noon called standup". Slots:
     *  `time` (java.util.Date, required), `title` (String?), `attendee`
     *  (String?), `durationMinutes` (Int, default 60). Handled by
     *  CreateEventHandler writing to CalendarContract (WRITE_CALENDAR). */
    CreateEvent,

    /** Fallback when no other intent matches with sufficient confidence. */
    Unknown,
    ;

    companion object {
        /** Case-insensitive lookup by name; returns [Unknown] if not found. */
        fun fromName(name: String): Intent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}
