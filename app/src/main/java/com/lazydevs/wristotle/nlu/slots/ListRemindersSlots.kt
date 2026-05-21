package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ListReminders] — none.
 * "What are my reminders" carries no parameters; the handler reads the whole
 * pending list.
 */
class ListRemindersSlots : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> = emptyMap()
}
