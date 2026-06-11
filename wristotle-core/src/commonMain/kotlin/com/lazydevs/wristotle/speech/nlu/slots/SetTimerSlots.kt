// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SetTimer].
 *
 * Pulls a duration out of phrases like:
 *   "set a timer for 10 minutes"   → 600
 *   "timer for 5 min"              → 300
 *   "set a 20 minute timer"        → 1200
 *   "timer for an hour and a half" → 5400
 *   "timer for 30 seconds"         → 30
 *   "set a timer for 10"           → 600  (bare number defaults to MINUTES)
 *
 * The bare-number default differs from [MediaSeekSlots] (which defaults to
 * seconds): a seek of "10" means 10 seconds, but a timer of "10" almost
 * always means 10 minutes. Returns `{seconds: Int}` when a duration is
 * found, empty map otherwise (handler reports the parse failure).
 */
class SetTimerSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val seconds = parseDurationSeconds(query) ?: return emptyMap()
        if (seconds <= 0) return emptyMap()
        return mapOf(SlotKeys.Seconds to seconds)
    }
}