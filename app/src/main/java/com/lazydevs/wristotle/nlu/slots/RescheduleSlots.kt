package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Reschedule]:
 *   - `time`   — the new [java.util.Date], from the legacy `TimeParser`
 *                (handles "in 10 minutes", "to 6pm", "until noon"). Required
 *                by the handler; bare "snooze" with no time fails gracefully.
 *   - `target` — optional descriptor of *which* reminder to move ("gym",
 *                "5pm"). Absent → the handler reschedules the most recent.
 *
 * `target` is built by chopping the trailing time clause, then stripping the
 * reschedule verbs + filler words — whatever remains names the reminder.
 */
class RescheduleSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        parseTime(query)?.let { out["time"] = it.date }
        val target = stripVerbBody(query.replace(STRIP_TIME_CLAUSE, ""), VERBS, FILLERS)
        if (target.isNotBlank()) out["target"] = target
        return out
    }

    private companion object {
        // Chop the trailing "to 6pm" / "in 10 minutes" / "until noon" clause so
        // it doesn't leak into the target descriptor.
        val STRIP_TIME_CLAUSE = Regex("""(?i)\s*(to|until|till|at|in|by|for|on|next|this)\s+[\w\s:.,]+$""")
        val VERBS = Regex(
            "(?i)\\b(snooze|reschedule|postpone|delay|push|move|bump|shift|remind me again|again)\\b",
        )
        val FILLERS = Regex(
            "(?i)\\b(the|my|that|this|a|an|reminder|reminders|alarm|alarms|it|please|back|i)\\b",
        )
    }
}
