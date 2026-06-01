package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.WorldTime] — a single
 * `location` slot holding the spoken place name. The handler resolves it to
 * a time zone via `TimeZoneResolver`.
 *
 * Pulls whatever follows a standalone "in" / "at" preposition:
 *   "what time is it in tokyo"             → "tokyo"
 *   "time in new york"                     → "new york"
 *   "what's the time in los angeles now"   → "los angeles"  (trailing "now" stripped)
 *
 * Returns an empty map (handler asks "which city?") when there's no place —
 * either no preposition at all, or the thing after it is a temporal phrase
 * ("in the morning") rather than a location. The bare "what time is it" form
 * never actually reaches here: the watch answers it locally and only forwards
 * location-qualified queries.
 */
class WorldTimeSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val loc = extractInOrAtLocation(query) ?: return emptyMap()
        return mapOf(SlotKeys.Location to loc)
    }
}

/** Typed read for the WorldTime `location` slot. */
fun Map<String, Any>.worldTimeLocation(): String? = this[SlotKeys.Location] as? String
