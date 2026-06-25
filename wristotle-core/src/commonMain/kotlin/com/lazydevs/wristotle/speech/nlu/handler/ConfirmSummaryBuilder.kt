// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handler

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.contacts.ResolvedContact
import com.lazydevs.wristotle.speech.nlu.handlers.DefaultTitles
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Pure formatter that renders an [IntentResult] as a short "action + body"
 * summary suitable for the watch confirm prompt + the dispatch debug log.
 *
 * Format: `action: <verb>\ndetails: <slots>` — readable, fits the watch's
 * 5-line chat, and the `body:` line spells out the resolved targets so
 * mishearings ("text dadd, …" → contact = "dadd") are obvious.
 */
object ConfirmSummaryBuilder {

    fun summary(r: IntentResult): String = when (r.intent) {
        Intent.Call          -> "action: call\ndetails: ${contactName(r)}"
        Intent.SendMessage   -> {
            // SMS gets a special-case verb so the prompt matches what
            // the user said ("text mom hi" → "action: text") rather than
            // the protocol abbreviation. Defaulting to "message" guards
            // the rare case where the slot extractor didn't populate app.
            val app = (r.slots[SlotKeys.App] as? String)?.takeIf { it.isNotEmpty() } ?: "message"
            val verb = if (app.equals("SMS", ignoreCase = true)) "text" else app.lowercase()
            val to = contactName(r)
            val body = (r.slots[SlotKeys.Body] as? String)?.take(MAX_BODY_PREVIEW)?.takeIf { it.isNotEmpty() }
            if (body == null) "action: $verb\ndetails: $to"
            else              "action: $verb\ndetails: $to $body"
        }
        Intent.Reminder      -> "action: reminder\ndetails: ${withTime(r, DefaultTitles.composeReminderTitle(slotStr(r, SlotKeys.Title)))}"
        Intent.Cancel        -> "action: cancel\ndetails: ${targetOrLatest(r)}"
        Intent.Reschedule    -> "action: reschedule\ndetails: ${targetOrLatest(r)} → ${timeOrDash(r)}"
        Intent.CreateEvent   -> "action: schedule\ndetails: ${withTime(r, DefaultTitles.composeEventTitle(slotStr(r, SlotKeys.Title), slotStr(r, SlotKeys.Attendee)))}"
        Intent.OpenApp       -> "action: open\ndetails: ${slot(r, SlotKeys.App)}"
        Intent.MediaPlay     -> "action: play\ndetails: ${slot(r, SlotKeys.App)}"
        Intent.MediaPause    -> "action: pause\ndetails: ${slotOrDash(r, SlotKeys.App)}"
        Intent.MediaPlayPause -> "action: play-pause\ndetails: ${slotOrDash(r, SlotKeys.App)}"
        Intent.MediaNext     -> "action: next\ndetails: ${slotOrDash(r, SlotKeys.App)}"
        Intent.MediaPrevious -> "action: previous\ndetails: ${slotOrDash(r, SlotKeys.App)}"
        Intent.MediaSeekForward,
        Intent.MediaSeekBackward -> "action: seek\ndetails: ${slotOrDash(r, SlotKeys.Seconds)}s"
        Intent.Note          -> "action: note\ndetails: ${slot(r, SlotKeys.Body)}"
        Intent.AppendNote    -> "action: append-note\ndetails: ${slot(r, SlotKeys.Body)}"
        Intent.AddTask       -> "action: add-task\ndetails: ${slot(r, SlotKeys.Body)}"
        Intent.ListTasks     -> "action: list-tasks\ndetails: -"
        Intent.CompleteTask  -> "action: complete\ndetails: ${slot(r, SlotKeys.Target)}"
        Intent.DeleteTask    -> "action: delete-task\ndetails: ${slot(r, SlotKeys.Target)}"
        Intent.SetTimer      -> "action: timer\ndetails: ${timerDuration(r)}"
        Intent.ListReminders -> "action: list-reminders\ndetails: -"
        Intent.Calendar      -> "action: calendar\ndetails: -"
        Intent.FindPhone     -> "action: find-phone\ndetails: -"
        Intent.Time          -> "action: time\ndetails: -"
        Intent.WorldTime     -> "action: world-time\ndetails: ${slot(r, "location")}"
        Intent.Calculate     -> "action: calculate\ndetails: ${slot(r, "expression")}"
        Intent.Weather       -> "action: weather\ndetails: ${slotOrDash(r, "location")}"
        Intent.SportScore    -> "action: sport\ndetails: ${slotOrDash(r, SlotKeys.Subject)}"
        Intent.AskAgent      -> "action: ask-agent\ndetails: ${slot(r, "query")}"
        Intent.MorningBrief  -> "action: morning-brief\ndetails: -"
        Intent.ShowCode      -> "action: show-code\ndetails: ${slotOrDash(r, SlotKeys.Subject)}"
        Intent.Battery       -> "action: battery\ndetails: -"
        Intent.Steps         -> "action: steps\ndetails: -"
        Intent.Vibrate       -> "action: vibrate\ndetails: -"
        Intent.CancelAlarm   -> "action: cancel-alarm\ndetails: ${timeOrDash(r)}"
        Intent.SetAlarm      -> "action: alarm\ndetails: ${timeOrDash(r)}"
        Intent.Unknown       -> "action: unknown\ndetails: ${r.rawQuery}"
    }

    private fun slot(r: IntentResult, key: String): String =
        (r.slots[key] as? String)?.takeIf { it.isNotEmpty() } ?: "?"

    /**
     * Bracket-wrapped contact display. Three shapes:
     *  - Resolved (PebbleListenerService.enrichResolvedContact set it):
     *        `[John]`
     *  - Spoken but unresolved (no Contacts row matched, or permission denied):
     *        `[NO_CONTACT] [next]`  (sentinel + echo of the actual lookup string)
     *  - No spoken contact at all (slot extractor produced nothing):
     *        `[NO_NAME]`
     */
    private fun contactName(r: IntentResult): String {
        val resolved = (r.slots[SlotKeys.ResolvedContact] as? ResolvedContact)
            ?.name?.takeIf { it.isNotEmpty() }
        if (resolved != null) return "[$resolved]"
        val spoken = (r.slots[SlotKeys.Contact] as? String)?.takeIf { it.isNotEmpty() }
        return if (spoken != null) "[NO_CONTACT] [$spoken]" else "[NO_NAME]"
    }

    private fun slotOrDash(r: IntentResult, key: String): String =
        (r.slots[key]?.toString())?.takeIf { it.isNotEmpty() } ?: "-"

    private fun targetOrLatest(r: IntentResult): String =
        (r.slots[SlotKeys.Target] as? String)?.takeIf { it.isNotEmpty() } ?: "latest reminder"

    private fun slotStr(r: IntentResult, key: String): String? =
        (r.slots[key] as? String)?.takeIf { it.isNotEmpty() }

    /** Appends the formatted time to an already-composed title (if present).
     *  The title itself comes from the shared [DefaultTitles] resolvers so the
     *  preview always matches what the handler will actually save. */
    private fun withTime(r: IntentResult, title: String): String {
        val time = (r.slots[SlotKeys.Time] as? Instant)?.let { formatWatchTime(it) }
        return if (time != null) "$title @ $time" else title
    }

    private fun timeOrDash(r: IntentResult): String =
        (r.slots[SlotKeys.Time] as? Instant)?.let { formatWatchTime(it) } ?: "-"

    /** Compact duration for SetTimer — "10m" / "1m 30s" / "45s". */
    private fun timerDuration(r: IntentResult): String {
        val secs = r.slots[SlotKeys.Seconds] as? Int ?: return "?"
        val m = secs / 60
        val s = secs % 60
        return when {
            m > 0 && s > 0 -> "${m}m ${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    /** "EEE h:mm a" — "Fri 3:00 PM". Adequate granularity for the user to
     *  spot a misheard time without burning chat lines. */
    private fun formatWatchTime(instant: Instant): String {
        val ldt: LocalDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val weekday = WEEKDAY[ldt.dayOfWeek.ordinal]
        val hour24 = ldt.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        val mm = ldt.minute.toString().padStart(2, '0')
        return "$weekday $hour12:$mm $ampm"
    }

    private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Cap on the SendMessage body preview shown in the confirm prompt.
     *  Sized so the prompt + verb + contact + body all fit on the watch's
     *  ~5-line chat surface without truncation jank. */
    private const val MAX_BODY_PREVIEW = 80
}
