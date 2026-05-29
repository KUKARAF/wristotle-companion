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
        val lower = query.lowercase()
        val match = LOCATION.find(lower) ?: return emptyMap()
        var loc = match.groupValues[1].trim()
        loc = TRAILING_FILLER.replace(loc, "").trim()
        loc = cleanNameToken(loc)
        if (loc.isEmpty() || loc in NON_LOCATIONS) return emptyMap()
        return mapOf("location" to loc)
    }

    private companion object {
        // Everything after the first standalone "in" / "at". The leading \b
        // keeps "in"/"at" from matching inside "raining" / "what".
        val LOCATION = Regex("(?i)\\b(?:in|at)\\s+(.+)$")
        // Trailing conversational tails that aren't part of the place name.
        val TRAILING_FILLER = Regex("(?i)\\b(right now|now|currently|at the moment|please|today|over there)\\b\\s*$")
        // "in <X>" phrases where X is a time-of-day word, not a place.
        val NON_LOCATIONS = setOf(
            "the morning", "the afternoon", "the evening", "the night",
            "morning", "afternoon", "evening", "night",
            "a bit", "a moment", "a sec", "a second", "a minute",
        )
    }
}

/** Typed read for the WorldTime `location` slot. */
internal fun Map<String, Any>.worldTimeLocation(): String? = this["location"] as? String
