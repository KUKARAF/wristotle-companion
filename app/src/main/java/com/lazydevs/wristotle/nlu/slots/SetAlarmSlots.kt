// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.handlers.parseTime
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
import com.lazydevs.wristotle.speech.nlu.slots.*
import java.util.Date

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SetAlarm].
 *
 * Voice creation grammar: *"set an alarm for 7am"*, *"wake me up at
 * 6:30"*, *"alarm for 7"*. Reuses [parseTime] (the same prettytime-nlp
 * parser the rest of the app uses) and carries the full [Date] in
 * `time` — [com.lazydevs.wristotle.handlers.SetAlarmHandler] reads
 * hour + minute off it.
 *
 * Returns an empty map when no time is parseable; the handler reports
 * "couldn't understand the time" so the user knows to retry.
 */
class SetAlarmSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        // Strip the creation prefix FIRST. prettytime-nlp choked on
        // "set an alarm for an hour from now" — it parses the query
        // but anchors on the verb phrase and returns "now" instead of
        // "+1h" (codeberg.org/wristotle/wristotle-companion/issues/8).
        // The raw form is still tried as a fallback for any
        // pathological case where stripping changes meaning.
        val stripped = query.replace(STRIP_PREFIX, "").trim()
        val date: Date = parseTime(stripped)?.date
            ?: parseTime(query)?.date
            ?: return emptyMap()
        return mapOf(SlotKeys.Time to date)
    }

    private companion object {
        // Same shape as ReminderSlots' STRIP_PREFIXES but trimmed to
        // the create-alarm openers PrefixHints accepts.
        val STRIP_PREFIX = Regex(
            """(?i)^\s*(set|setup|start|put|create|new|add)\s+(an?\s+|my\s+)?alarm\s+(for|at|to)?\s*""",
        )
    }
}