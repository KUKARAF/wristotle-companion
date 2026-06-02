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
        val date: Date = parseTime(query)?.date ?: return emptyMap()
        return mapOf(SlotKeys.Time to date)
    }
}
