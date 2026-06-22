// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.worldTimeLocation
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Handles [Intent.WorldTime] — "what time is it in Tokyo".
 *
 * Resolves the spoken location to a [TimeZone] via [TimeZoneResolver], then
 * hands off to [WorldTimeFormat] for presentation (current time there + a short
 * weekday/offset suffix relative to the phone's own zone):
 *
 *   "It's 7:42 AM in Tokyo\n(Wed, 13h ahead)."
 *   "It's 9:15 PM in London\n(8h behind)."
 *
 * R5 — lifted from :app. java.util.* replaced with kotlinx-datetime; the pure
 * formatting was extracted to [WorldTimeFormat] (testable) and the output
 * strings preserved exactly so the watch chat copy doesn't change.
 */
class WorldTimeHandler : ActionHandler {

    override val tag: String = "world_time"
    override val intent: Intent = Intent.WorldTime

    override suspend fun handle(result: IntentResult): String {
        val location = result.slots.worldTimeLocation()
            ?: return "Which city?\nTry \"what time is it in Tokyo\"."
        val zone = TimeZoneResolver.resolve(location)
            ?: return "Couldn't find the time zone for \"$location\"."
        return WorldTimeFormat.format(zone, TimeZone.currentSystemDefault(), location, Clock.System.now())
    }
}
