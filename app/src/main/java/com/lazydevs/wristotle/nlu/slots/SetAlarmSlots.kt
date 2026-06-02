package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SetAlarm].
 *
 * Voice creation grammar: *"set an alarm for 7am"*, *"wake me up at
 * 6:30"*, *"alarm for 7"*. Reuses [parseTime] (the same prettytime-nlp
 * parser the rest of the app uses) and carries the full [Date] in
 * `time` — [com.lazydevs.wristotle.handlers.SetAlarmHandler] reads
 * hour + minute off it.
 *
 * Returns an empty map when no time is parseable; the handler reports
 * "couldn't understand the time" so the user knows to retry.
 */
class SetAlarmSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        // Try the raw query first — prettytime handles short forms like
        // "set an alarm for 7am" directly. If it fails (often happens on
        // "for 3:30 pm" with longer queries where the parser anchors on
        // the wrong token), strip the creation prefix so prettytime sees
        // only the time fragment.
        val date: Date = parseTime(query)?.date
            ?: parseTime(query.replace(STRIP_PREFIX, "").trim())?.date
            ?: return emptyMap()
        return mapOf(SlotKeys.Time to date)
    }

    private companion object {
        // Same shape as ReminderSlots' STRIP_PREFIXES but trimmed to
        // the create-alarm openers PrefixHints accepts.
        val STRIP_PREFIX = Regex(
            """(?i)^\s*(set|setup|start|put|create|new|add)\s+(an?\s+|my\s+)?alarm\s+(for|at|to)?\s*""",
        )
    }
}
