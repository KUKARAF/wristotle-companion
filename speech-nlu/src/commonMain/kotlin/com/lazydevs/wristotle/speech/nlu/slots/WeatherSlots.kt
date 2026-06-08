// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

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
 * Empty map (no `location` key) means a bare query → `WeatherHandler`
 * then tries the phone's last-known location.
 */
class WeatherSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val loc = extractInOrAtLocation(query) ?: return emptyMap()
        return mapOf(SlotKeys.Location to loc)
    }
}

/** Typed read for the Weather `location` slot. */
fun Map<String, Any>.weatherLocation(): String? = this[SlotKeys.Location] as? String