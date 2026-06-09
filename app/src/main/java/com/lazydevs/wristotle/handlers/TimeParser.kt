// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.parsing.ParsedTime
import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import com.lazydevs.wristotle.speech.nlu.slots.WORD_NUMBERS as CORE_WORD_NUMBERS
import kotlinx.datetime.Instant
import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.util.Date

/**
 * JVM (Android) implementation of [TimeParser] — wraps prettytime-nlp + the
 * word-form-number / "an hour" normalizer that's been on the Android side
 * since v0.x.
 *
 * Singleton because PrettyTimeParser construction is non-trivial and the
 * parser itself is thread-safe.
 */
object PrettyTimeTimeParser : TimeParser {

    private val parser = PrettyTimeParser()

    override fun parse(query: String): ParsedTime? {
        val normalized = normalizeNumbers(query)
        val dates: List<Date> = parser.parse(normalized)
        val date = dates.firstOrNull() ?: return null
        return ParsedTime(
            instant = Instant.fromEpochMilliseconds(date.time),
            matchedText = "",
        )
    }

    // ── normalisation ─────────────────────────────────────────────────

    // Pebble voice transcription outputs word-form numbers; prettytime-nlp
    // only handles digits. Derived from the shared `:wristotle-core` table
    // (covers 1-19 + every 10s + every compound n*10+m up to 99 in both
    // space- and hyphen-joined forms) plus a few alarm-specific helpers
    // ("o'clock" → empty so "set an alarm for 11 o'clock" lands as "11").
    // The bare articles "a" / "an" are dropped — ARTICLE_BEFORE_TIME_UNIT
    // handles them with unit context; replacing them blindly would turn
    // "ask agent" into "ask 1gent".
    private val WORD_NUMBERS: Map<String, String> = buildMap {
        for ((word, value) in CORE_WORD_NUMBERS) {
            if (word == "a" || word == "an") continue
            put(word, value.toString())
        }
        put("o'clock", "")
    }

    private val WORD_PATTERN = Regex(
        WORD_NUMBERS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    // After digit replacement, "10 30 pm" should become "10:30 pm".
    private val HOUR_MINUTE_UNJOINED = Regex("""(?<![:\d])([1-9]|1[0-2]) ([0-5]\d)(?![:\d])""")

    // Indefinite article in front of a duration unit. prettytime-nlp
    // understands "in a minute" / "in an hour" (it's tolerant of the
    // article when "in" comes before), but blows up on "an hour from
    // now" — it can't quantify the article in that shape and falls back
    // to "now". Rewrite to "1 <unit>" upstream so prettytime sees a
    // numeric quantity in both shapes.
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
}

// ── backward-compat shim ─────────────────────────────────────────────

/**
 * Legacy top-level fn kept so call sites that aren't ready to take a
 * [TimeParser] dependency yet (still in :app/handlers) compile unchanged.
 * Delegates to the singleton implementation.
 *
 * Slot extractors in :speech-nlu commonMain take a [TimeParser] constructor
 * param and don't use this shim. Future R3 work removes this shim as
 * the remaining :app callers get their own injection.
 */
fun parseTime(text: String): ParsedTime? = PrettyTimeTimeParser.parse(text)
