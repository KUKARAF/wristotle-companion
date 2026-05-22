package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Reminder]:
 *   - `time`  — parsed [java.util.Date], from the legacy `TimeParser`
 *               (PrettyTime + word-form number normalization).
 *   - `title` — the reminder body, with the reminder-prefix and any
 *               recognised time phrase stripped, then capitalised.
 *
 * Both slots are populated independently — if the time fails to parse,
 * the handler reports "Couldn't understand the time"; if the title strips
 * to nothing, the handler falls back to capitalizing the original query.
 */
class ReminderSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        parseTime(query)?.let { out["time"] = it.date }
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
