// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.worldTimeLocation
import kotlin.math.abs
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Handles [Intent.WorldTime] — "what time is it in Tokyo".
 *
 * Resolves the spoken location to a [TimeZone] via [TimeZoneResolver], then
 * formats the current time there plus a short suffix giving the weekday (only
 * when it's a different calendar day from the phone) and the offset relative
 * to the phone's own zone:
 *
 *   "It's 7:42 AM in Tokyo\n(Wed, 13h ahead)."
 *   "It's 9:15 PM in London\n(8h behind)."
 *
 * R5 — lifted from :app. java.util.{SimpleDateFormat, Calendar, Date,
 * TimeZone, Locale} replaced with kotlinx-datetime. Output strings preserved
 * exactly so the watch chat copy doesn't change.
 */
class WorldTimeHandler : ActionHandler {

    override val tag: String = "world_time"
    override val intent: Intent = Intent.WorldTime

    override suspend fun handle(result: IntentResult): String {
        val location = result.slots.worldTimeLocation()
            ?: return "Which city?\nTry \"what time is it in Tokyo\"."
        val zone = TimeZoneResolver.resolve(location)
            ?: return "Couldn't find the time zone for \"$location\"."
        return format(zone, location, Clock.System.now())
    }

    private fun format(zone: TimeZone, spoken: String, now: Instant): String {
        val targetLdt = now.toLocalDateTime(zone)
        val timeStr = formatTime(targetLdt)
        val city = titleCase(spoken)
        val suffix = buildSuffix(zone, now, targetLdt)
        return "It's $timeStr in $city\n($suffix)."
    }

    /** "Wed, 13h ahead" / "8h behind" / "same time" — weekday only when the
     *  target is on a different calendar day from the phone's local zone. */
    private fun buildSuffix(zone: TimeZone, now: Instant, targetLdt: LocalDateTime): String {
        val parts = mutableListOf<String>()

        val localLdt = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val differentDay = targetLdt.date != localLdt.date
        if (differentDay) {
            parts.add(WEEKDAY[targetLdt.dayOfWeek.ordinal])
        }

        // Compute the offset between zones at this Instant by projecting
        // each wall-clock LDT back through UTC and taking the difference.
        val targetEpoch = targetLdt.toInstant(TimeZone.UTC).toEpochMilliseconds()
        val localEpoch = localLdt.toInstant(TimeZone.UTC).toEpochMilliseconds()
        val diffMin = ((targetEpoch - localEpoch) / 60_000L).toInt()
        parts.add(offsetPhrase(diffMin))
        return parts.joinToString(", ")
    }

    private fun offsetPhrase(diffMinutes: Int): String {
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

    private fun formatTime(ldt: LocalDateTime): String {
        val hour24 = ldt.hour
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        val mm = ldt.minute.toString().padStart(2, '0')
        return "$hour12:$mm $ampm"
    }

    private fun titleCase(s: String): String =
        s.split(" ").joinToString(" ") { word ->
            if (word.lowercase() in UPPERCASE_WORDS) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }

    private companion object {
        val UPPERCASE_WORDS = setOf("nyc", "la", "sf", "dc", "uk", "uae", "usa", "us")
        val WEEKDAY = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}
