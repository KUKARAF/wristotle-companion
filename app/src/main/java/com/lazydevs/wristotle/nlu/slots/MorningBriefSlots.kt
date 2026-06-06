package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.MorningBrief].
 *
 * No structured slots in v1. The brief is whole-by-default — every
 * section the handler knows about (calendar / alarms / reminders /
 * tasks / notes) is included and the result is joined + trimmed for
 * the watch chat surface.
 *
 * Future shape: an optional `sections` slot for queries like
 * *"morning brief without alarms"* or *"just meetings today"*. Skipped
 * for now because (a) we want to see the full brief first to tune
 * length + ordering, and (b) section-name vocabulary inside the brief
 * (alarms vs reminders vs tasks) tends to confuse users until they
 * see the default once.
 */
class MorningBriefSlots : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> = emptyMap()
}
