package com.lazydevs.wristotle.handlers

import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.util.Date

private val parser = PrettyTimeParser()

data class ParsedTime(val date: Date, val matchedText: String)

// Pebble voice transcription outputs word-form numbers; prettytime-nlp only handles digits.
private val WORD_NUMBERS = mapOf(
    "twelve" to "12", "eleven" to "11", "ten" to "10",
    "nine" to "9", "eight" to "8", "seven" to "7", "six" to "6",
    "five" to "5", "four" to "4", "three" to "3", "two" to "2", "one" to "1",
    "fifty-five" to "55", "fifty five" to "55", "fifty" to "50",
    "forty-five" to "45", "forty five" to "45", "forty" to "40",
    "thirty-five" to "35", "thirty five" to "35", "thirty" to "30",
    "twenty-five" to "25", "twenty five" to "25", "twenty" to "20",
    "fifteen" to "15",
    "o'clock" to "",
)

private val WORD_PATTERN = Regex(
    WORD_NUMBERS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
    RegexOption.IGNORE_CASE,
)

// After digit replacement, "10 30 pm" should become "10:30 pm".
// Matches H MM or HH MM that is not already colon-separated.
private val HOUR_MINUTE_UNJOINED = Regex("""(?<![:\d])([1-9]|1[0-2]) ([0-5]\d)(?![:\d])""")

// Indefinite article in front of a duration unit. prettytime-nlp
// understands "in a minute" / "in an hour" (it's tolerant of the
// article when "in" comes before), but blows up on "an hour from
// now" — it can't quantify the article in that shape and falls back
// to "now". Rewrite to "1 <unit>" upstream so prettytime sees a
// numeric quantity in both shapes.
//
// Tight enough not to touch articles outside the time domain:
// "remind me to buy a book" → "a" sits before "book", not a unit,
// so nothing changes.
//
// Closes codeberg.org/wristotle/wristotle-companion/issues/8.
private val ARTICLE_BEFORE_TIME_UNIT = Regex(
    """\b(?:a|an)\s+(second|minute|hour|day|week|month|year)(s?)\b""",
    RegexOption.IGNORE_CASE,
)

private fun normalizeNumbers(text: String): String {
    val articled = ARTICLE_BEFORE_TIME_UNIT.replace(text) { "1 ${it.groupValues[1]}${it.groupValues[2]}" }
    val digits = WORD_PATTERN.replace(articled) { WORD_NUMBERS[it.value.lowercase()] ?: "" }
        .replace("  ", " ").trim()
    return HOUR_MINUTE_UNJOINED.replace(digits) { "${it.groupValues[1]}:${it.groupValues[2]}" }
}

fun parseTime(text: String): ParsedTime? {
    val normalized = normalizeNumbers(text)
    val dates = parser.parse(normalized)
    val date = dates.firstOrNull() ?: return null
    return ParsedTime(date, "")
}
