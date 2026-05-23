package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Pure formatter that renders an [IntentResult] as a short "action + body"
 * summary suitable for both:
 *
 *  - **Confirm prompt on the watch** (Phase A3+) — the user sees this before
 *    SELECT-ing to dispatch a destructive command.
 *  - **Debug log line in [PebbleListenerService]** (Phase A1.5) — lets us
 *    inspect what the classifier + slot extractor produced for every query,
 *    before any wire protocol is in place.
 *
 * Format: `action: <verb>\nbody: <slots>` — readable, fits the watch's
 * 5-line chat, and the `body:` line spells out the resolved targets so
 * mishearings ("text dadd, …" → contact = "dadd") are obvious.
 *
 * No Android imports — unit-testable as a pure function.
 */
object ConfirmSummaryBuilder {

    fun summary(r: IntentResult): String = when (r.intent) {
        Intent.Call          -> "action: call\nbody: [${slot(r, "contact")}]"
        Intent.Sms           -> {
            val to = slot(r, "contact")
            val body = (r.slots["body"] as? String)?.take(80)?.takeIf { it.isNotEmpty() }
            if (body == null) "action: text\nbody: [$to]"
            else              "action: text\nbody: [$to] $body"
        }
        Intent.Reminder      -> "action: reminder\nbody: ${slot(r, "title")}"
        // Cancel/Reschedule slots use the key `target` (the reminder
        // descriptor). When absent the handler operates on the most-recent
        // pin — surface that as "latest reminder" so the user knows what's
        // about to happen rather than seeing a bare "?" or empty body.
        Intent.Cancel        -> "action: cancel\nbody: ${targetOrLatest(r)}"
        Intent.Reschedule    -> "action: reschedule\nbody: ${targetOrLatest(r)}"
        Intent.CreateEvent   -> "action: create-event\nbody: ${slot(r, "title")}"
        Intent.OpenApp       -> "action: open\nbody: ${slot(r, "app")}"
        Intent.MediaPlay     -> "action: play\nbody: ${slot(r, "app")}"
        Intent.MediaPause    -> "action: pause\nbody: ${slotOrDash(r, "app")}"
        Intent.MediaPlayPause -> "action: play-pause\nbody: ${slotOrDash(r, "app")}"
        Intent.MediaNext     -> "action: next\nbody: ${slotOrDash(r, "app")}"
        Intent.MediaPrevious -> "action: previous\nbody: ${slotOrDash(r, "app")}"
        Intent.MediaSeekForward,
        Intent.MediaSeekBackward -> "action: seek\nbody: ${slotOrDash(r, "seconds")}s"
        Intent.Note          -> "action: note\nbody: ${slot(r, "body")}"
        Intent.AppendNote    -> "action: append-note\nbody: ${slot(r, "body")}"
        Intent.ListReminders -> "action: list-reminders\nbody: -"
        Intent.Calendar      -> "action: calendar\nbody: -"
        Intent.FindPhone     -> "action: find-phone\nbody: -"
        Intent.Time          -> "action: time\nbody: -"
        Intent.Battery       -> "action: battery\nbody: -"
        Intent.Steps         -> "action: steps\nbody: -"
        Intent.Vibrate       -> "action: vibrate\nbody: -"
        Intent.Unknown       -> "action: unknown\nbody: ${r.rawQuery}"
    }

    private fun slot(r: IntentResult, key: String): String =
        (r.slots[key] as? String)?.takeIf { it.isNotEmpty() } ?: "?"

    private fun slotOrDash(r: IntentResult, key: String): String =
        (r.slots[key]?.toString())?.takeIf { it.isNotEmpty() } ?: "-"

    private fun targetOrLatest(r: IntentResult): String =
        (r.slots["target"] as? String)?.takeIf { it.isNotEmpty() } ?: "latest reminder"
}
