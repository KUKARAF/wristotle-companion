package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.CancelAlarm].
 *
 * Returns an empty map for the bare *"cancel alarm"* / *"stop the alarm"*
 * phrasing — the handler interprets the missing slot as "cancel all watch
 * alarms" and sends epoch=0 to the watch.
 *
 * If a wall-clock time is parseable from the query (*"cancel 7am alarm"*,
 * *"stop the 6:30 alarm"*, *"dismiss alarm at 8pm"*), the slot carries a
 * full [Date] — the handler reads hour + minute off it to look up the
 * matching Room rows and cancel each one's watch leg by wireEpoch.
 *
 * Reuses [parseTime] (the same prettytime-nlp parser
 * Reminders / Calendar / the old SetAlarmSlots used) so the user can speak
 * any phrasing the rest of the app understands.
 */
class CancelAlarmSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val date: Date = parseTime(query)?.date ?: return emptyMap()
        return mapOf(SlotKeys.Time to date)
    }
}
