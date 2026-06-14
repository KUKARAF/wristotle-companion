// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handler

import com.lazydevs.wristotle.speech.nlu.Intent

/**
 * Classifies an [Intent] as **destructive** — i.e. dispatching it produces
 * a side effect that the user might not want to silently happen if the
 * classifier or slot extractor got something wrong.
 *
 * Gates the confirm-before-dispatch prompt. When the user has the Watch
 * "Confirm action" toggle on AND the routed intent is destructive, the
 * companion sends a `CONFIRM_PROMPT` to the watch instead of dispatching
 * immediately. Read-only intents (Time / Battery / Steps / FindPhone /
 * ListReminders / Calendar query / pause-next-previous-seek media / Note /
 * AppendNote) dispatch straight through even with the toggle on.
 *
 * Rationale per intent:
 *  - **Call / SendMessage** — sends to another person; the user's original
 *    concern. SendMessage covers SMS, WhatsApp, Telegram, Signal — every
 *    way to send a message from the app.
 *  - **Reminder / Cancel / Reschedule** — modify persistent watch-side
 *    timeline pins.
 *  - **CreateEvent** — writes to the user's phone calendar.
 *  - **OpenApp / MediaPlay** — focus-stealing app launch.
 *  - **Excluded:** Note / AppendNote — personal data, undo-by-delete is
 *    cheap, often dictated in bursts. MediaPause / Next / Previous / Seek —
 *    toggling music isn't destructive in the user-data sense.
 */
fun Intent.requiresConfirm(): Boolean = when (this) {
    Intent.Call,
    Intent.SendMessage,
    Intent.Reminder,
    Intent.Cancel,
    Intent.Reschedule,
    Intent.CreateEvent,
    Intent.OpenApp,
    Intent.MediaPlay,
    Intent.CompleteTask, // marks a row done; user-data mutation
    Intent.DeleteTask,   // removes a row; harder to recover
    Intent.SetAlarm,     // a misheard time wakes you at the wrong hour
    Intent.SetTimer      // a misheard duration is annoying to catch after the fact
        -> true

    Intent.MediaPause,
    Intent.MediaPlayPause,
    Intent.MediaNext,
    Intent.MediaPrevious,
    Intent.MediaSeekForward,
    Intent.MediaSeekBackward,
    Intent.Note,
    Intent.AppendNote,
    Intent.AddTask,        // matches Note rationale: personal data, often dictated in bursts
    Intent.ListTasks,      // read-only
    Intent.ListReminders,
    Intent.Calendar,
    Intent.FindPhone,
    Intent.Time,
    Intent.WorldTime,     // read-only clock lookup
    Intent.Calculate,     // read-only arithmetic
    Intent.Weather,       // read-only network lookup
    Intent.SportScore,    // read-only sports lookup
    Intent.AskAgent,      // phase B1 is pure Q&A — read-only by construction.
                          // B2's tool-calling will hand destructive decisions
                          // to the LLM; revisit the confirm gate then.
    Intent.MorningBrief,   // aggregates read-only data — calendar / alarms /
                           // reminders / tasks / notes — no mutations.
    Intent.Battery,
    Intent.Steps,
    Intent.Vibrate,
    Intent.CancelAlarm,    // reversible — user re-enables in the companion Alarms card
    Intent.Unknown -> false
}
