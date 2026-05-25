package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * Format: `action: <verb>\ndetails: <slots>` — readable, fits the watch's
 * 5-line chat, and the `body:` line spells out the resolved targets so
 * mishearings ("text dadd, …" → contact = "dadd") are obvious.
 *
 * No Android imports — unit-testable as a pure function.
 */
object ConfirmSummaryBuilder {

    fun summary(r: IntentResult): String = when (r.intent) {
        Intent.Call          -> "action: call\ndetails: [${slot(r, "contact")}]"
        // SendMessage covers every send-a-message path — SMS via
        // programmatic SmsManager, plus WhatsApp / Telegram / Signal
        // via assisted-send deep-link. The verb (action) is the app
        // name itself — "action: WhatsApp / details: [Mom] on my way"
        // reads naturally on the watch chat surface. SMS gets a
        // special-case verb of `"text"` so the prompt matches the
        // user's spoken word ("text mom hi" → "action: text") rather
        // than the protocol abbreviation. Defaulting to "message"
        // guards against the rare case where the classifier picks
        // SendMessage but the slot extractor didn't populate the app
        // field.
        Intent.SendMessage   -> {
            val app = (r.slots["app"] as? String)?.takeIf { it.isNotEmpty() } ?: "message"
            val verb = if (app.equals("SMS", ignoreCase = true)) "text" else app.lowercase()
            val to = slot(r, "contact")
            val body = (r.slots["body"] as? String)?.take(80)?.takeIf { it.isNotEmpty() }
            if (body == null) "action: $verb\ndetails: [$to]"
            else              "action: $verb\ndetails: [$to] $body"
        }
        Intent.Reminder      -> "action: reminder\ndetails: ${titleWithTime(r, defaultTitle = "Reminder")}"
        // Cancel/Reschedule slots use the key `target` (the reminder
        // descriptor). When absent the handler operates on the most-recent
        // pin — surface that as "latest reminder" so the user knows what's
        // about to happen rather than seeing a bare "?" or empty body.
        Intent.Cancel        -> "action: cancel\ndetails: ${targetOrLatest(r)}"
        // Reschedule shows target → new time so the user verifies both ends
        // of the change ("Move latest reminder → Fri 9:00 AM?").
        Intent.Reschedule    -> "action: reschedule\ndetails: ${targetOrLatest(r)} → ${timeOrDash(r)}"
        // CreateEvent title defaults to "Meeting" in the handler when none
        // was spoken; mirror that here so the confirm doesn't show "?".
        // "schedule" reads more naturally than "create-event" — matches the
        // verb the user said ("schedule a meeting").
        Intent.CreateEvent   -> "action: schedule\ndetails: ${titleWithTime(r, defaultTitle = "Meeting")}"
        Intent.OpenApp       -> "action: open\ndetails: ${slot(r, "app")}"
        Intent.MediaPlay     -> "action: play\ndetails: ${slot(r, "app")}"
        Intent.MediaPause    -> "action: pause\ndetails: ${slotOrDash(r, "app")}"
        Intent.MediaPlayPause -> "action: play-pause\ndetails: ${slotOrDash(r, "app")}"
        Intent.MediaNext     -> "action: next\ndetails: ${slotOrDash(r, "app")}"
        Intent.MediaPrevious -> "action: previous\ndetails: ${slotOrDash(r, "app")}"
        Intent.MediaSeekForward,
        Intent.MediaSeekBackward -> "action: seek\ndetails: ${slotOrDash(r, "seconds")}s"
        Intent.Note          -> "action: note\ndetails: ${slot(r, "body")}"
        Intent.AppendNote    -> "action: append-note\ndetails: ${slot(r, "body")}"
        Intent.AddTask       -> "action: add-task\ndetails: ${slot(r, "body")}"
        Intent.ListTasks     -> "action: list-tasks\ndetails: -"
        Intent.ListReminders -> "action: list-reminders\ndetails: -"
        Intent.Calendar      -> "action: calendar\ndetails: -"
        Intent.FindPhone     -> "action: find-phone\ndetails: -"
        Intent.Time          -> "action: time\ndetails: -"
        Intent.Battery       -> "action: battery\ndetails: -"
        Intent.Steps         -> "action: steps\ndetails: -"
        Intent.Vibrate       -> "action: vibrate\ndetails: -"
        Intent.Unknown       -> "action: unknown\ndetails: ${r.rawQuery}"
    }

    private fun slot(r: IntentResult, key: String): String =
        (r.slots[key] as? String)?.takeIf { it.isNotEmpty() } ?: "?"

    private fun slotOrDash(r: IntentResult, key: String): String =
        (r.slots[key]?.toString())?.takeIf { it.isNotEmpty() } ?: "-"

    private fun targetOrLatest(r: IntentResult): String =
        (r.slots["target"] as? String)?.takeIf { it.isNotEmpty() } ?: "latest reminder"

    /**
     * Format the title + time pair for Reminder / CreateEvent. The handler
     * defaults a missing title to [defaultTitle], so we do the same so the
     * confirm prompt accurately reflects what would land. Time is omitted
     * when not parseable — the handler will fail with "Couldn't understand
     * the time" anyway, but at least the title surfaces.
     */
    private fun titleWithTime(r: IntentResult, defaultTitle: String): String {
        val title = (r.slots["title"] as? String)?.takeIf { it.isNotEmpty() } ?: defaultTitle
        val time = (r.slots["time"] as? Date)?.let { TIME_FMT.get()!!.format(it) }
        return if (time != null) "$title @ $time" else title
    }

    private fun timeOrDash(r: IntentResult): String =
        (r.slots["time"] as? Date)?.let { TIME_FMT.get()!!.format(it) } ?: "-"

    /**
     * Short, watch-friendly time format. Example: "Fri 3:00 PM" or
     * "May 25 3:00 PM" if a date >7 days out — adequate granularity for the
     * user to spot a misheard time without burning chat lines.
     *
     * Wrapped in [ThreadLocal] because [SimpleDateFormat] is not thread-safe;
     * the call site is single-threaded today (PebbleListenerService dispatch
     * coroutine) but if classify/dispatch ever moves off-thread the shared-
     * SDF latent bug would silently corrupt date strings.
     * `DateTimeFormatter` would also work but requires core-library
     * desugaring below API 26 (project minSdk = 24); ThreadLocal SDF is the
     * zero-config option.
     */
    private val TIME_FMT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
    }
}
