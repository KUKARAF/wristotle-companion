package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.CreateEvent]:
 *   - `time`            — [Date] start time, parsed via the shared `parseTime`
 *                         (PrettyTime + word-form numbers). Required by the
 *                         handler; absent → it reports it couldn't parse a time.
 *   - `title`           — explicit event title from "called/titled/about/for X".
 *                         Absent → the handler defaults to "Meeting" (+ attendee).
 *   - `attendee`        — the "with X" participant, used to build a default
 *                         title ("Meeting with Alex") when no explicit title.
 *   - `durationMinutes` — Int, parsed from "for one hour" / "30 minute" /
 *                         "half hour". Absent → the handler defaults to 60.
 *
 * Title/attendee are read off the raw transcript (both are keyword-anchored).
 * A trailing time clause is stripped from the captured title so the spoken
 * clock time doesn't bleed into the name whether the title leads or trails
 * the time ("…called standup at 3pm" and "…at 3pm called standup" both →
 * "Standup"); the attendee pattern already stops before a time token.
 */
class CreateEventSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()

        parseTime(query)?.let { out["time"] = it.date }

        // Title/attendee are read off the raw query — both regexes are
        // keyword-anchored (called/titled/about, with), so a time phrase
        // before the title ("…at 2pm called standup") is preserved. A time
        // phrase AFTER the title ("…called standup at 3pm") is greedily
        // captured by `(.+)$`, so strip a trailing time clause back off.
        TITLE.find(query)?.groupValues?.getOrNull(2)
            ?.replace(TRAILING_TIME, "")
            ?.let { cleanToken(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { out["title"] = it.replaceFirstChar(Char::uppercaseChar) }

        ATTENDEE.find(query)?.groupValues?.getOrNull(1)
            ?.let { cleanToken(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { out["attendee"] = it.replaceFirstChar(Char::uppercaseChar) }

        extractDuration(query.lowercase())?.let { out["durationMinutes"] = it }

        return out
    }

    /** "for one hour" / "30 minute meeting" / "half hour" → minutes. */
    private fun extractDuration(lower: String): Int? {
        if (HALF_HOUR.containsMatchIn(lower)) return 30
        HOURS.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: WORD_NUMS[m.groupValues[1]] ?: return@let
            return n * 60
        }
        MINUTES.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: WORD_NUMS[m.groupValues[1]] ?: return@let
            return n
        }
        return null
    }

    /** Drop leading/trailing filler ("the", "a", "an") and punctuation. */
    private fun cleanToken(raw: String): String =
        raw.trim().trim('.', ',', '!', '?')
            .replace(LEADING_ARTICLE, "")
            .trim()

    private companion object {
        // "… called standup" / "titled X" / "about X". Deliberately NOT
        // "for X" — "for tomorrow at 3" is a time clause, not a title.
        val TITLE = Regex("""(?i)\b(called|titled|about)\s+(.+)$""")
        // "with Alex" / "with the team" — stop before a trailing time/date
        // clause OR a title keyword ("with Alex called standup" → "Alex").
        val ATTENDEE = Regex("""(?i)\bwith\s+([A-Za-z][\w' ]*?)(?:\s+(?:on|at|tomorrow|today|tonight|next|this|called|titled|about)\b|$)""")
        val LEADING_ARTICLE = Regex("""^(?i)(the|a|an)\s+""")
        // A trailing " at/on/… <rest>" clause to peel off a greedily-captured
        // title. The leading \s+ means a title-initial keyword ("next steps")
        // is left intact.
        val TRAILING_TIME = Regex("""(?i)\s+(?:at|on|by|in|from|tomorrow|today|tonight|next|this)\b.*$""")

        val HALF_HOUR = Regex("""(?i)\bhalf (?:an )?hour\b""")
        val HOURS = Regex("""(?i)\b(\d{1,2}|one|two|three|four|five|six)\s*(?:hour|hr)s?\b""")
        val MINUTES = Regex("""(?i)\b(\d{1,3}|fifteen|thirty|forty five|forty-five|sixty)\s*(?:minute|min)s?\b""")
        val WORD_NUMS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "fifteen" to 15, "thirty" to 30,
            "forty five" to 45, "forty-five" to 45, "sixty" to 60,
        )
    }
}

/** Type-safe slot reads for the CreateEvent handler. */
fun Map<String, Any>.eventTime(): Date? = this["time"] as? Date
fun Map<String, Any>.eventTitle(): String? = this["title"] as? String
fun Map<String, Any>.eventAttendee(): String? = this["attendee"] as? String
fun Map<String, Any>.eventDurationMinutes(default: Int = 60): Int =
    (this["durationMinutes"] as? Int) ?: default
