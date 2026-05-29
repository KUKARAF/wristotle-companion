package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Weather] — a single,
 * **optional** `location` slot holding the spoken place.
 *
 * Pulls whatever follows a standalone "in" / "at":
 *   "weather in tokyo"                         → "tokyo"
 *   "what's the weather in new york"           → "new york"
 *   "what's the weather"                       → (no key — bare query)
 *
 * Mirrors [WorldTimeSlots]'s extraction logic but is independent so the two
 * intents can evolve. Empty map (no `location` key) means a bare query →
 * `WeatherHandler` then tries the phone's last-known location.
 */
class WeatherSlots : SlotExtractor {

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
        val LOCATION = Regex("(?i)\\b(?:in|at)\\s+(.+)$")
        val TRAILING_FILLER = Regex("(?i)\\b(right now|now|currently|at the moment|please|today|over there)\\b\\s*$")
        // Temporal "in <X>" phrases that aren't places (mirrors WorldTimeSlots).
        val NON_LOCATIONS = setOf(
            "the morning", "the afternoon", "the evening", "the night",
            "morning", "afternoon", "evening", "night",
            "a bit", "a moment", "a sec", "a second", "a minute",
        )
    }
}

/** Typed read for the Weather `location` slot. */
internal fun Map<String, Any>.weatherLocation(): String? = this["location"] as? String
