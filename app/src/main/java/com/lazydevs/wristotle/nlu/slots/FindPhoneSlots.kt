package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.FindPhone] — empty.
 * Trigger phrases ("find my phone", "ping my phone", "where's my phone")
 * carry no parameters today.
 */
class FindPhoneSlots : SlotExtractor {
    override suspend fun extract(query: String): Map<String, Any> = emptyMap()
}
