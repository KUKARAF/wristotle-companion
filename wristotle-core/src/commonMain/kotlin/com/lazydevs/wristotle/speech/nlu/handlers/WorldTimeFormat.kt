// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import kotlin.math.abs
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure world-clock presentation, extracted from [WorldTimeHandler] so it's
 * unit-testable. The local zone is a parameter (the handler passes
 * `TimeZone.currentSystemDefault()`), which keeps [format] / [buildSuffix]
 * deterministic instead of reading the system zone internally.
 *
 * Output strings are preserved exactly — the watch chat copy must not change.
 *   "It's 7:42 AM in Tokyo\n(Wed, 13h ahead)."
 *   "It's 9:15 PM in London\n(8h behind)."
 */
internal object WorldTimeFormat {

    fun format(targetZone: TimeZone, localZone: TimeZone, spoken: String, now: Instant): String {
        val targetLdt = now.toLocalDateTime(targetZone)
        val timeStr = formatTime(targetLdt)
        val city = titleCase(spoken)
        val suffix = buildSuffix(localZone, now, targetLdt)
        return "It's $timeStr in $city\n($suffix)."
    }

    /** "Wed, 13h ahead" / "8h behind" / "same time" — weekday only when the
     *  target is on a different calendar day from [localZone]. */
    fun buildSuffix(localZone: TimeZone, now: Instant, targetLdt: LocalDateTime): String {
        val parts = mutableListOf<String>()
        val localLdt = now.toLocalDateTime(localZone)
        if (targetLdt.date != localLdt.date) parts.add(WEEKDAY[targetLdt.dayOfWeek.ordinal])

        val targetEpoch = targetLdt.toInstant(TimeZone.UTC).toEpochMilliseconds()
        val localEpoch = localLdt.toInstant(TimeZone.UTC).toEpochMilliseconds()
        val diffMin = ((targetEpoch - localEpoch) / 60_000L).toInt()
        parts.add(offsetPhrase(diffMin))
        return parts.joinToString(", ")
    }

    fun offsetPhrase(diffMinutes: Int): String {
        if (diffMinutes == 0) return "same time"
        val h = abs(diffMinutes) / 60
        val m = abs(diffMinutes) % 60
        val magnitude = when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h${m}m"
        }
        return if (diffMinutes > 0) "$magnitude ahead" else "$magnitude behind"
    }

    fun formatTime(ldt: LocalDateTime): String {
        val hour24 = ldt.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        val mm = ldt.minute.toString().padStart(2, '0')
        return "$hour12:$mm $ampm"
    }

    fun titleCase(s: String): String =
        s.split(" ").joinToString(" ") { word ->
            if (word.lowercase() in UPPERCASE_WORDS) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }

    private val UPPERCASE_WORDS = setOf("nyc", "la", "sf", "dc", "uk", "uae", "usa", "us")
    private val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}
