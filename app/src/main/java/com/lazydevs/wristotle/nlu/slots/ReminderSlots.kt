package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.ReminderSettings
import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Reminder]:
 *   - `time`  — parsed [java.util.Date], from the legacy `TimeParser`
 *               (PrettyTime + word-form number normalization). Defaults
 *               to now + [defaultOffsetMinProvider] minutes (configured
 *               in Settings → Reminders, default 30) when the user
 *               didn't say a time ("remind me to buy milk"). The confirm
 *               prompt surfaces the defaulted time so the user can
 *               cancel + re-dictate with an explicit time if needed.
 *   - `title` — the reminder body, with the reminder-prefix and any
 *               recognised time phrase stripped, then capitalised. If
 *               the title strips to nothing, the handler falls back to
 *               capitalizing the original query.
 *
 * The provider is a lambda (rather than a [ReminderSettings] handle) so
 * the slot stays Android-free for unit testing — see `ReminderSlotsTest`.
 */
class ReminderSlots(
    private val defaultOffsetMinProvider: () -> Int = { ReminderSettings.DEFAULT_OFFSET_MIN },
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        out[SlotKeys.Time] = parseTime(query)?.date
            ?: Date(System.currentTimeMillis() + defaultOffsetMinProvider() * 60_000L)
        val title = buildTitle(query)
        if (title.isNotBlank()) out[SlotKeys.Title] = title
        return out
    }

    private fun buildTitle(transcription: String): String =
        transcription
            .replace(STRIP_PREFIXES, "")
            .replace(STRIP_LEADING_TIME_THEN_TO, "")
            .replace(STRIP_TIME_PHRASES, "")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }

    private companion object {
        // `\b` wraps the optional connectors so "remind me to call" strips
        // the lead-in "to" but "remind me tomorrow" does NOT eat the "to"
        // hidden inside "tomorrow" — issue #4 surfaced this bug while
        // chasing the leading-time-clause case.
        val STRIP_PREFIXES = Regex(
            """(?i)^(remind me (\bto\b|\babout\b|\bthat\b)?|reminder (\bto\b|\babout\b)?|set a reminder (\bto\b|\bfor\b)?|set an? alarm (\bfor\b|\bto\b)?|schedule a reminder (\bfor\b|\bto\b)?|wake me up|tell me when|ping me|buzz me)\s*""",
        )
        // Handles the "remind me <time> to <task>" shape (issue #4):
        // after STRIP_PREFIXES drops "remind me ", the residual is
        // "in two hours to check the tables", and STRIP_TIME_PHRASES
        // (anchored at end-of-string with a leading \s+) can't reach a
        // time clause that sits at the START. This regex peels a
        // leading time lead-in up to the task-introducing "to ".
        // Lazy match between the lead-in and "to" keeps "remind me
        // tomorrow to call mom" → "call mom" without over-eating
        // anything past the first "to".
        val STRIP_LEADING_TIME_THEN_TO = Regex(
            """(?i)^\s*\b(in|at|by|on|tomorrow|next|this|every|later|tonight)\b[\w\s:.,]*?\bto\b\s+""",
        )
        // Note: "to" is intentionally NOT a lead-in — "remind me TO call" uses
        // "to" to introduce the task, not a time. The shared builder handles the
        // word-boundary safety (the "at" in "chat" isn't stripped mid-word).
        val STRIP_TIME_PHRASES = trailingTimeClauseRegex(
            setOf("at", "in", "by", "on", "next", "this", "every"),
        )
    }
}
