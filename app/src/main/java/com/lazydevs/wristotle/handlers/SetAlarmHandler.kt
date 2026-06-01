package com.lazydevs.wristotle.handlers

import android.content.Context
import android.content.Intent as AndroidIntent
import android.provider.AlarmClock
import android.util.Log
import com.lazydevs.wristotle.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import java.util.Calendar
import java.util.Date

private const val TAG = "SetAlarmHandler"

/**
 * Handles [Intent.SetAlarm] — sets a phone alarm via the system clock app.
 *
 * Fires `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI` so a clock app
 * that honours it (Google Clock does) sets the alarm silently in the
 * background. OEM clocks that ignore SKIP_UI fall back to showing their
 * own pre-filled UI — still a working set, just one extra tap.
 *
 * Set-only: Android exposes no API to enumerate or cancel a scheduled
 * alarm, so there's no companion list/cancel counterpart. The user
 * manages existing alarms in the clock app.
 *
 * The hour + minute come off the `time` slot's [Date]; the date portion
 * is ignored (the clock app schedules the next occurrence of that
 * wall-clock time).
 */
class SetAlarmHandler(private val context: Context) : ActionHandler {

    override val tag: String = "set_alarm"
    override val intent: Intent = Intent.SetAlarm

    override suspend fun handle(result: IntentResult): String {
        val time = result.slots[SlotKeys.Time] as? Date
            ?: return "Couldn't understand the time.\nTry \"set an alarm for 7am\"."

        val cal = Calendar.getInstance().apply { this.time = time }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        val alarmIntent = AndroidIntent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (alarmIntent.resolveActivity(context.packageManager) == null) {
            return "No clock app found to set the alarm."
        }

        return try {
            context.startActivity(alarmIntent)
            "Alarm set for ${formatClock(hour, minute)}."
        } catch (t: Throwable) {
            // Most likely an Android 14 background-activity-launch refusal —
            // same wall as open-app. Granting Wristotle "Display over other
            // apps" unblocks it (see the BAL knowledge note).
            Log.w(TAG, "startActivity(SET_ALARM) failed", t)
            "Couldn't set the alarm."
        }
    }

    /** 24h hour + minute → "7:00 AM" / "11:30 PM". */
    private fun formatClock(hour: Int, minute: Int): String {
        val period = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "%d:%02d %s".format(h12, minute, period)
    }
}
