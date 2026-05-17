package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Cancel] — empty for now.
 * CancelReminderHandler ignores the transcription and always cancels the
 * most-recent pin; if/when we support "cancel my 5 pm reminder" style
 * queries this is where a `target` slot would land.
 */
class CancelSlots : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> = emptyMap()
}
