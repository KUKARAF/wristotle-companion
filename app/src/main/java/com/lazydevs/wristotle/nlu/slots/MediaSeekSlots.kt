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
        return mapOf(SlotKeys.Seconds to seconds)
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
        val DIGIT_DURATION = Regex("(\\d+)\\s*($DURATION_UNIT_ALT)?\\b")
        val WORD_DURATION = Regex("\\b($WORD_NUMBER_ALT)\\s*($DURATION_UNIT_ALT)?\\b")
    }
}
