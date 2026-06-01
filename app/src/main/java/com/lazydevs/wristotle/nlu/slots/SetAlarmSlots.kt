package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SetAlarm].
 *
 * Pulls a time-of-day out of phrases like:
 *   "set an alarm for 7am"
 *   "wake me up at 6:30"
 *   "alarm for 7"
 *
 * Reuses [parseTime] (the same prettytime-nlp parser Reminders / Calendar
 * use) and keeps the full [Date] in the `time` slot — the handler reads
 * the hour + minute off it for `AlarmClock.EXTRA_HOUR` / `EXTRA_MINUTES`.
 * The date portion is irrelevant to an alarm (the clock app schedules the
 * next occurrence of that wall-clock time) but parseTime returns a full
 * Date, so we carry it as-is and let the handler project it down.
 *
 * Returns an empty map when no time is found — the handler then reports
 * "couldn't understand the time" rather than setting a wrong alarm.
 */
class SetAlarmSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val date: Date = parseTime(query)?.date ?: return emptyMap()
        return mapOf(SlotKeys.Time to date)
    }
}
