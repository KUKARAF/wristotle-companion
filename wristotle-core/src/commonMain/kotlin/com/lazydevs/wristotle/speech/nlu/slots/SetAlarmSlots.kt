// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.parsing.TimeParser
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import kotlinx.datetime.Instant

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SetAlarm].
 *
 * Voice creation grammar: *"set an alarm for 7am"*, *"wake me up at
 * 6:30"*, *"alarm for 7"*. Uses the injected [TimeParser] and carries
 * the full [Instant] in `time` — the handler reads hour + minute off it.
 *
 * Returns an empty map when no time is parseable; the handler reports
 * "couldn't understand the time" so the user knows to retry.
 *
 * R2 batch 4 — lifted from :app; [TimeParser] injected via constructor.
 */
class SetAlarmSlots(
    private val timeParser: TimeParser,
) : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        // Strip the creation prefix FIRST. prettytime-nlp choked on
        // "set an alarm for an hour from now" — it parses the query
        // but anchors on the verb phrase and returns "now" instead of
        // "+1h" (codeberg.org/wristotle/wristotle-companion/issues/8).
        // The raw form is still tried as a fallback for any
        // pathological case where stripping changes meaning.
        //
        // Also normalise "a.m." → "am" / "p.m." → "pm" — prettytime-nlp's
        // AM/PM tokenizer doesn't survive the dots, which is why
        // "set an alarm for 7:51 p.m." failed in codeberg #12.
        val normalized = AMPM_DOTTED.replace(query) { m -> "${m.groupValues[1]}m" }
        val stripped = normalized.replace(STRIP_PREFIX, "").trim()

        // Compound relative durations ("one hour and four minutes from
        // now") flow through SetTimer's duration parser — prettytime-nlp
        // anchors on the verb phrase and can't sum compound units, but
        // [parseDurationSeconds] sums every "<n> <unit>" pair. SetTimer
        // and SetAlarm share the helper so the same input shape resolves
        // identically on both intents (codeberg #12).
        if (RELATIVE_DURATION_MARKER.containsMatchIn(stripped)) {
            val seconds = parseDurationSeconds(stripped)
            if (seconds != null && seconds > 0) {
                return mapOf(SlotKeys.Time to Clock.System.now() + seconds.seconds)
            }
        }

        val instant: Instant = timeParser.parse(stripped)?.instant
            ?: timeParser.parse(normalized)?.instant
            ?: return emptyMap()
        return mapOf(SlotKeys.Time to instant)
    }

    private companion object {
        // Same shape as ReminderSlots' STRIP_PREFIXES but trimmed to
        // the create-alarm openers PrefixHints accepts.
        val STRIP_PREFIX = Regex(
            """(?i)^\s*(set|setup|start|put|create|new|add)\s+(an?\s+|my\s+)?alarm\s+(for|at|to)?\s*""",
        )

        /** Catches "a.m." / "p.m." / "A.M." / "P.M." and folds them into
         *  the dotless form before prettytime-nlp sees them. */
        val AMPM_DOTTED = Regex("""(?i)\b([ap])\.m\.""")

        /** Cheap precondition gate for the SetTimer-style fast path —
         *  only trigger when the stripped query mentions a relative
         *  marker, otherwise prettytime-nlp owns the parse. */
        val RELATIVE_DURATION_MARKER = Regex("""(?i)\bfrom now\b|\bin\s+\d""")
    }
}
