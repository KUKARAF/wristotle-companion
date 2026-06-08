// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import android.content.Intent as AndroidIntent
import android.provider.AlarmClock
import android.util.Log
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

private const val TAG = "SetTimerHandler"

/**
 * Handles [Intent.SetTimer] — starts a countdown timer via the system
 * clock app.
 *
 * Fires `AlarmClock.ACTION_SET_TIMER` with the duration in seconds and
 * `EXTRA_SKIP_UI`. Same SKIP_UI / OEM-fallback + background-activity-launch
 * caveats as [SetAlarmHandler].
 */
class SetTimerHandler(private val context: Context) : ActionHandler {

    override val tag: String = "set_timer"
    override val intent: Intent = Intent.SetTimer

    override suspend fun handle(result: IntentResult): String {
        val seconds = result.slots[SlotKeys.Seconds] as? Int
            ?: return "Couldn't understand the duration.\nTry \"set a timer for 10 minutes\"."

        // Deliberately NOT EXTRA_SKIP_UI=true (unlike SetAlarmHandler).
        // Google Clock, given SKIP_UI, runs the timer in the background and
        // never surfaces the Timers tab — the countdown fires but the user
        // can't see or cancel it. A running timer is something you want to
        // watch, so let the Clock app open + show it. (Alarms are
        // set-and-forget, so SetAlarm keeps SKIP_UI.)
        val timerIntent = AndroidIntent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (timerIntent.resolveActivity(context.packageManager) == null) {
            return "No clock app found to set the timer."
        }

        return try {
            context.startActivity(timerIntent)
            "Timer set for ${formatDuration(seconds)}."
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity(SET_TIMER) failed", t)
            "Couldn't set the timer."
        }
    }

    /** Seconds → "10 minutes" / "1 hour 30 minutes" / "45 seconds". */
    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = buildList {
            if (h > 0) add(plural(h, "hour"))
            if (m > 0) add(plural(m, "minute"))
            if (s > 0) add(plural(s, "second"))
        }
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" ")
    }

    private fun plural(n: Int, unit: String): String =
        if (n == 1) "1 $unit" else "$n ${unit}s"
}