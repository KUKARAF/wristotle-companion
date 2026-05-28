package com.lazydevs.wristotle.handlers

import android.content.Context
import android.content.Intent as AndroidIntent
import android.provider.AlarmClock
import android.util.Log
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
        val seconds = result.slots["seconds"] as? Int
            ?: return "Couldn't understand the duration.\nTry \"set a timer for 10 minutes\"."

        val timerIntent = AndroidIntent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
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
