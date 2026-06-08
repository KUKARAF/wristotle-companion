// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.worldTimeLocation
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Handles [Intent.WorldTime] — "what time is it in Tokyo".
 *
 * Resolves the spoken location to a [TimeZone] via [TimeZoneResolver], then
 * formats the current time there plus a short suffix giving the weekday (only
 * when it's a different calendar day from the phone) and the offset relative
 * to the phone's own zone:
 *
 *   "It's 7:42 AM in Tokyo\n(Wed, 13h ahead)"
 *   "It's 9:15 PM in London\n(8h behind)"
 *
 * Pure `java.util` — no Context, no network. Fails soft with a watch-friendly
 * hint when the location is missing or unrecognised.
 */
class WorldTimeHandler : ActionHandler {

    override val tag: String = "world_time"
    override val intent: Intent = Intent.WorldTime

    override suspend fun handle(result: IntentResult): String {
        val location = result.slots.worldTimeLocation()
            ?: return "Which city?\nTry \"what time is it in Tokyo\"."
        val zone = TimeZoneResolver.resolve(location)
            ?: return "Couldn't find the time zone for \"$location\"."
        return format(zone, location, Date())
    }

    /** Visible for the shape of the output; [now] is injectable for clarity. */
    private fun format(zone: TimeZone, spoken: String, now: Date): String {
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }
        val timeStr = timeFmt.format(now)
        val city = titleCase(spoken)
        val suffix = buildSuffix(zone, now)
        return "It's $timeStr in $city\n($suffix)."
    }

    /** "Wed, 13h ahead" / "8h behind" / "same time" — weekday only when the
     *  target is on a different calendar day from the phone's local zone. */
    private fun buildSuffix(zone: TimeZone, now: Date): String {
        val parts = mutableListOf<String>()

        val targetCal = Calendar.getInstance(zone)
        val localCal = Calendar.getInstance()
        val differentDay = targetCal.get(Calendar.DAY_OF_YEAR) != localCal.get(Calendar.DAY_OF_YEAR) ||
            targetCal.get(Calendar.YEAR) != localCal.get(Calendar.YEAR)
        if (differentDay) {
            val dayFmt = SimpleDateFormat("EEE", Locale.US).apply { timeZone = zone }
            parts.add(dayFmt.format(now))
        }

        val diffMin = (zone.getOffset(now.time) - TimeZone.getDefault().getOffset(now.time)) / 60_000
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

    private fun titleCase(s: String): String =
        s.split(" ").joinToString(" ") { word ->
            if (word.lowercase() in UPPERCASE_WORDS) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }

    private companion object {
        // Spoken abbreviations that read better fully capitalised.
        val UPPERCASE_WORDS = setOf("nyc", "la", "sf", "dc", "uk", "uae", "usa", "us")
    }
}