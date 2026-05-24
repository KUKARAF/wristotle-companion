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
        out["time"] = parseTime(query)?.date
            ?: Date(System.currentTimeMillis() + defaultOffsetMinProvider() * 60_000L)
        val title = buildTitle(query)
        if (title.isNotBlank()) out["title"] = title
        return out
    }

    private fun buildTitle(transcription: String): String =
        transcription
            .replace(STRIP_PREFIXES, "")
            .replace(STRIP_TIME_PHRASES, "")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }

    private companion object {
        val STRIP_PREFIXES = Regex(
            """(?i)^(remind me (to|about|that)?|reminder (to|about)?|set a reminder (to|for)?|set an? alarm (for|to)?|schedule a reminder (for|to)?|wake me up|tell me when|ping me|buzz me)\s*""",
        )
        // Note: "to" is intentionally NOT a lead-in — "remind me TO call" uses
        // "to" to introduce the task, not a time. The shared builder handles the
        // word-boundary safety (the "at" in "chat" isn't stripped mid-word).
        val STRIP_TIME_PHRASES = trailingTimeClauseRegex(
            setOf("at", "in", "by", "on", "next", "this", "every"),
        )
    }
}
