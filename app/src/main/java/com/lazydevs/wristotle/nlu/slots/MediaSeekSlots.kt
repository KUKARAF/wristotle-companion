package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.MediaSeekForward]
 * and `MediaSeekBackward`. The same extractor handles both — direction
 * comes from the intent, magnitude (seconds) is the only slot.
 *
 * Pulls a duration out of phrases like:
 *   "skip ahead 30 seconds"
 *   "rewind ten seconds"
 *   "fast forward fifteen"          → assumes seconds
 *   "go back a minute"              → 60
 *   "skip forward two minutes"      → 120
 *   "rewind"                        → no slot; handler applies the
 *                                     default for the direction
 *
 * Returns `{seconds: Int}` when a duration is found, empty map otherwise
 * so the handler can apply its own default (30s forward / 10s back).
 */
class MediaSeekSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val lower = query.lowercase()
        val seconds = parseDuration(lower) ?: return emptyMap()
        return mapOf("seconds" to seconds)
    }

    private fun parseDuration(text: String): Int? {
        // First try digits — fastest path and covers the bulk of inputs.
        val digitMatch = DIGIT_DURATION.find(text)
        if (digitMatch != null) {
            val n = digitMatch.groupValues[1].toIntOrNull() ?: return null
            val unit = digitMatch.groupValues[2]
            return n * unitSeconds(unit)
        }
        // Word-form numbers ("ten seconds", "a minute"). Only the
        // common small values — anything beyond "thirty" is rare in
        // voice commands and falls through to handler default.
        val wordMatch = WORD_DURATION.find(text) ?: return null
        val word = wordMatch.groupValues[1].trim()
        val unit = wordMatch.groupValues[2]
        val n = WORD_NUMBERS[word] ?: return null
        return n * unitSeconds(unit)
    }

    private fun unitSeconds(unit: String): Int = when {
        unit.startsWith("min") -> 60
        unit.startsWith("hour") -> 3600
        else -> 1 // "seconds", "secs", "s", or no unit defaults to seconds
    }

    private companion object {
        // 30, 30s, 30 seconds, 2 minutes, etc.
        val DIGIT_DURATION = Regex(
            "(\\d+)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)?\\b"
        )
        // "ten seconds", "a minute", "fifteen", etc.
        val WORD_DURATION = Regex(
            "\\b(a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|forty-five|forty five|sixty|ninety)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)?\\b"
        )
        val WORD_NUMBERS: Map<String, Int> = mapOf(
            "a" to 1, "an" to 1, "one" to 1,
            "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
            "forty" to 40, "forty-five" to 45, "forty five" to 45,
            "sixty" to 60, "ninety" to 90,
        )
    }
}
