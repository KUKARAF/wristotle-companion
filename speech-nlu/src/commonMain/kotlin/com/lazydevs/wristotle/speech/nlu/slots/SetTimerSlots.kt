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
        val lower = query.lowercase()
        val seconds = parseTimerDuration(lower) ?: return emptyMap()
        if (seconds <= 0) return emptyMap()
        return mapOf(SlotKeys.Seconds to seconds)
    }

    private fun parseTimerDuration(text: String): Int? {
        var total = 0
        var matchedAny = false

        // Digit path first — sum every "<n> <unit>" pair so "1 hour 30
        // minutes" works. Digits are safe to bare-match (a stray "10"
        // can only be the duration), so a unitless digit defaults to
        // minutes.
        DIGIT_UNIT.findAll(text).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            total += n * unitSeconds(m.groupValues[2])
            matchedAny = true
        }
        if (matchedAny) return total

        // Word-form path — REQUIRES an explicit unit. Without it, "a" / "an"
        // / "one" would match the article in "set **a** timer" and add a
        // phantom minute. So only count "ten minutes" / "an hour", never a
        // bare "a" / "ten".
        WORD_UNIT.findAll(text).forEach { m ->
            val unit = m.groupValues[2]
            if (unit.isEmpty()) return@forEach
            val n = WORD_NUMBERS[m.groupValues[1].trim()] ?: return@forEach
            total += n * unitSeconds(unit)
            matchedAny = true
        }
        if (matchedAny) return total

        return null
    }

    /** Empty / absent unit defaults to MINUTES for timers. */
    private fun unitSeconds(unit: String): Int = when {
        unit.startsWith("sec") || unit == "s" -> 1
        unit.startsWith("hour") || unit.startsWith("hr") || unit == "h" -> 3600
        else -> 60 // "minute(s)", "min", "m", or no unit
    }

    private companion object {
        val DIGIT_UNIT = Regex("(\\d+)\\s*($DURATION_UNIT_ALT)?\\b")
        val WORD_UNIT = Regex("\\b($WORD_NUMBER_ALT)\\s*($DURATION_UNIT_ALT)?\\b")
    }
}