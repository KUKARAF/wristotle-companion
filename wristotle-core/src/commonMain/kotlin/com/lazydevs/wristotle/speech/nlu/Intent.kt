// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

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

    /** World-clock query — "what time is it in Tokyo", "time in London".
     *  Slot: `location` (String — the spoken city / country / region).
     *  Handled by WorldTimeHandler: TimeZoneResolver maps the spoken
     *  location to an Olson zone id (`java.util.TimeZone`, no network),
     *  then formats the current time there plus the day + offset relative
     *  to the phone's own zone.
     *
     *  Distinct from [Time], which the watch answers locally for the
     *  wearer's own zone and never forwards — only location-qualified
     *  queries reach the companion (the watch's local "time" command
     *  bows out when it sees a standalone "in", so "what time is it in
     *  X" falls through to COMPANION_QUERY). There is intentionally no
     *  companion handler for plain [Time]. */
    WorldTime,
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
     *  As of Phase A2, SMS is also routed here — the
     *  MessagingTargets registry has an SMS entry that uses
     *  [android.telephony.SmsManager.sendTextMessage] for programmatic
     *  delivery (no compose screen, no tap). The slot extractor picks
     *  app="SMS" by default when no third-party app is named. The
     *  third-party apps still gatekeep their playback / send APIs to
     *  Google-Assistant-signed callers, so the deep-link path is the
     *  only sideloaded option there. */
    SendMessage,

    /** Add a new task to the user's checklist ("add buy milk to my tasks",
     *  "add task buy milk", "new task pick up package"). Slot: `body`
     *  (String) — the task text with the lead-in stripped. Handled by
     *  AddTaskHandler persisting to the tasks Room store. Distinct from
     *  [Note] (free-form capture, no checkable state) and [Reminder]
     *  (time-triggered alert). See messaging-apps.md / tasks.md. */
    AddTask,

    /** Read-only list of the user's pending tasks ("what are my tasks",
     *  "list my tasks", "show my tasks"). No slots. Handled by
     *  ListTasksHandler — returns the first 5 pending tasks formatted
     *  for the watch chat. */
    ListTasks,

    /** Mark a pending task as done ("complete buy milk", "mark call
     *  dentist done", "finish the laundry"). Slot: `target` (String) —
     *  text to substring-match against pending tasks, OR one of the
     *  "last task" shortcut keywords (handler resolves). Destructive —
     *  goes through the confirm gate when enabled. */
    CompleteTask,

    /** Remove a task entirely ("delete buy milk from my tasks", "remove
     *  task call the dentist"). Slot: `target` (String) — same
     *  matching strategy as CompleteTask. Distinct from [Cancel]
     *  (reminders only) — DeleteTask requires an explicit
     *  "task" / "tasks" / "todo" keyword in the query so "cancel X"
     *  / "remove X" without that anchor stay with Cancel. Destructive. */
    DeleteTask,

    /** Voice alarm creation ("set an alarm for 7am", "wake me up at
     *  6:30"). Slot: `time` (java.util.Date — hour + minute are read
     *  off it). Routes through SetAlarmHandler → AlarmRepository +
     *  AlarmDispatcher, the same pipeline the companion's UI editor
     *  uses. Destination comes from `AlarmSettings.defaultDestination`
     *  (Phone / Watch / Both; set in the Alarms card; default Phone).
     *  Destructive — gated by the confirm-before-dispatch surface so a
     *  misheard time gets a one-line preview first. */
    SetAlarm,

    /** Cancel a pending alarm ("cancel alarm", "cancel 7am alarm",
     *  "stop the alarm"). Slot: optional `time` (java.util.Date — hour +
     *  minute parsed from the query; null for bare cancel).
     *
     *  Bare "cancel alarm" sends epoch=0 to the watch (cancel-all
     *  semantic). Time-qualified "cancel 7am alarm" looks up the matching
     *  Room row(s), grabs each row's currently-scheduled wireEpoch, and
     *  sends per-row cancels. Phone-leg alarms can't be cancelled
     *  programmatically (Android AlarmClock dead-end) — the response text
     *  surfaces that constraint when phone-only or Both-destination
     *  alarms are touched. CREATION lives in the companion's Alarms
     *  Settings card (Settings → ⏰ Alarms & Reminders); voice is
     *  cancel-only as of alarms-v2. */
    CancelAlarm,

    /** Start a countdown timer via the system clock app ("set a timer for
     *  10 minutes", "timer for 5 min"). Slot: `seconds` (Int). Handled by
     *  SetTimerHandler firing AlarmClock.ACTION_SET_TIMER with EXTRA_LENGTH
     *  + EXTRA_SKIP_UI. A bare number ("timer for 10") defaults to minutes. */
    SetTimer,

    /** Current weather — "what's the weather", "weather in Tokyo", "is it
     *  raining". Slot: `location` (String?, optional — the spoken place).
     *  Handled by WeatherHandler: a place-named query goes through geocoding
     *  + a current-weather lookup; a bare query uses the phone's last-known
     *  location (if `ACCESS_COARSE_LOCATION` is granted) or returns a hint.
     *  **First network-using intent in the app** — provider is open-meteo by
     *  default (no key) with optional OpenWeather (user-provided key) via the
     *  Weather settings card; output is short and watch-friendly. See
     *  `weather.md`. Read-only — not in the confirm gate. */
    Weather,

    /** Sports scores — "when do the Warriors play next", "did Arsenal win",
     *  "what's the live score", "Premier League table". Slots: `sportKind`
     *  ({NEXT, LAST, LIVE, STANDINGS}, inferred from the verb — defaults to
     *  NEXT) + `subject` (String?, optional — the spoken team/league; omitted
     *  when the user means a saved favorite, e.g. "did we win"). Handled by
     *  SportHandler over the generic `sportskapi` library (ESPN-backed today);
     *  output is short + watch-friendly. Network read-only — not in the confirm
     *  gate. See the sport-events plan. */
    SportScore,

    /** On-device calculator — "what's 15% of 80", "25 plus 17", "96 divided
     *  by 4". Slot: `expression` (String — a normalised arithmetic string,
     *  e.g. "15 / 100 * 80"). CalculateSlots turns spoken operators
     *  ("plus"/"times"/"divided by") and the "X% of Y" / "X% off Y" forms
     *  into symbols; CalculateHandler evaluates via the pure [Calculator]
     *  (recursive-descent over the four operators and parens) and formats the
     *  result. Pure arithmetic, no network. Read-only — not in the confirm gate. */
    Calculate,

    /** Free-form LLM query — "ask agent <question>" / "ask claude <question>".
     *  Slot: `query` (String — the question text with the lead-in stripped).
     *  Handled by AskAgentHandler: routes to the user-configured LLM provider
     *  (Anthropic or OpenAI-compatible — see AskAgentSettings) and returns the
     *  response text. Phase B1 is plain Q&A only — no MCP tool calling yet
     *  (that's phase B2). Read-only — not in the confirm gate. */
    AskAgent,

    /** One-shot aggregate of "what's on my plate today" — voiced as
     *  "morning brief" / "brief me" / "what's my day" / "summary of
     *  today". No slots in v1 (the brief is whole-by-default; an
     *  optional `sections` filter is a v2 idea). Handler aggregates
     *  one-line summaries from the existing CalendarRepository,
     *  AlarmRepository, PinStore (reminders), TaskRepository, and
     *  NoteRepository for the today-range and joins them with
     *  newlines, trimmed for the watch chat surface. Read-only —
     *  not in the confirm gate. Unread-message section deferred to
     *  a follow-up. */
    MorningBrief,

    /** Recall a saved QR/barcode to the watch by spoken name or alias —
     *  "show my tesco", "pull up my clubcard". Companion resolves the alias/
     *  label against the saved codes and tells the watch which to render. */
    ShowCode,

    /** Fallback when no other intent matches with sufficient confidence. */
    Unknown,
    ;

    companion object {
        /** Case-insensitive lookup by name; returns [Unknown] if not found. */
        fun fromName(name: String): Intent =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}