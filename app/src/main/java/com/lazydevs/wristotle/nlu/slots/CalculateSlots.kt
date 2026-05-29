package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Calculate] — a single
 * `expression` slot holding a normalised arithmetic string for [Calculator].
 *
 * Turns spoken math into symbols:
 *   "what's 25 plus 17"        → "25 + 17"
 *   "100 minus 30"             → "100 - 30"
 *   "12 times 8"               → "12 * 8"
 *   "96 divided by 4"          → "96 / 4"
 *   "what's 15% of 80"         → "( 15 / 100 * 80 )"
 *   "20 percent off 50"        → "( 50 - 20 / 100 * 50 )"
 *
 * Steps: lowercase → rewrite the "X% of/off Y" forms → replace operator words
 * with symbols → strip everything that isn't a digit / operator / paren / dot.
 * That last strip removes the lead-in words ("what's", "calculate", "how much
 * is") for free. **Digits only** — word-form numbers ("fifteen") aren't
 * supported; Whisper transcribes arithmetic numerically in practice.
 *
 * Returns an empty map (handler reports the parse failure) when nothing
 * numeric survives.
 */
class CalculateSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        var s = query.lowercase()
        s = PERCENT_OFF.replace(s) { m -> "( ${m.groupValues[2]} - ${m.groupValues[1]} / 100 * ${m.groupValues[2]} )" }
        s = PERCENT_OF.replace(s) { m -> "( ${m.groupValues[1]} / 100 * ${m.groupValues[2]} )" }
        for ((re, sym) in OPERATORS) s = re.replace(s, sym)
        s = NON_MATH.replace(s, " ")
        s = MULTI_SPACE.replace(s, " ").trim()
        if (!s.any { it.isDigit() }) return emptyMap()
        return mapOf("expression" to s)
    }

    private companion object {
        // "15% of 80" / "15 percent of 80" → 15/100*80.
        val PERCENT_OF = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+(\\d+(?:\\.\\d+)?)")
        // "20% off 50" / "20 percent off 50" → 50 - 20/100*50 (discount).
        val PERCENT_OFF = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+off\\s+(\\d+(?:\\.\\d+)?)")
        // Operator words → symbols. Multi-word forms first so "divided by"
        // is consumed before a bare "by" could matter.
        val OPERATORS: List<Pair<Regex, String>> = listOf(
            Regex("(?i)\\bmultiplied by\\b") to "*",
            Regex("(?i)\\bdivided by\\b") to "/",
            Regex("(?i)\\btimes\\b") to "*",
            Regex("(?i)\\bplus\\b") to "+",
            Regex("(?i)\\bminus\\b") to "-",
            Regex("(?i)\\bover\\b") to "/",
            Regex("(?i)\\bx\\b") to "*",
        )
        val NON_MATH = Regex("[^0-9.+\\-*/() ]")
        val MULTI_SPACE = Regex("\\s+")
    }
}

/** Typed read for the Calculate `expression` slot. */
internal fun Map<String, Any>.calcExpression(): String? = this["expression"] as? String
