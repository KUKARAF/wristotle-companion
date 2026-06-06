package com.lazydevs.wristotle.handlers.persistent

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lazydevs.wristotle.handlers.PinStore
import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.handlers.ReminderSettings

private const val TAG = "PersistentReminder"

/**
 * Schedules + cancels + restores the AlarmManager alarms that drive persistent
 * reminders.
 *
 * A reminder is persistent when [ReminderRecord.isPersistent] is `true`. At
 * its `timeMs` the first phone notification fires (alongside the watch
 * timeline pin). Each fire decrements [ReminderRecord.attemptsRemaining] and
 * re-arms the next at `now + intervalMin * 60_000`, until the counter hits
 * zero, the user taps **Stop** on the notification, or the underlying pin is
 * cancelled through another path.
 *
 * Exact alarms are used so the "remind me at 3pm" promise survives Doze.
 * `USE_EXACT_ALARM` is auto-granted at install on Android 13+ for
 * alarm-clock-shaped apps (reminder/timer/calendar); `SCHEDULE_EXACT_ALARM`
 * covers 12-and-older. Inexact is the silent fallback if the platform
 * refuses — better a late nag than none.
 *
 * The notification channel is created up-front in [WristotleApplication.onCreate]
 * via [ensureNotificationChannel] so the channel exists before the first fire.
 */
class PersistentReminderScheduler(private val app: Context) {

    private val alarmManager = app.getSystemService(AlarmManager::class.java)
    private val notificationManager = app.getSystemService(NotificationManager::class.java)
    private val pinStore = PinStore(app)

    /**
     * Idempotent — call from [WristotleApplication.onCreate]. Creates the
     * "Persistent reminders" channel if missing. Importance HIGH so a nag
     * surfaces as a heads-up; vibration enabled by default to match the
     * timeline-pin firing UX.
     */
    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Persistent reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description =
                "Re-fires reminders you marked as persistent until you tap Stop."
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Arm the first fire for [record]. Skips silently if the record isn't
     * persistent or already has no attempts left — keeps callers cheap to
     * use unconditionally. A past [timeMs] (rare, e.g. clock skew at insert)
     * is treated as "fire now-ish" so the user still gets the nag.
     */
    fun schedule(record: ReminderRecord) {
        if (!record.isPersistent || record.attemptsRemaining <= 0) return
        val timeMs = record.timeMs ?: return
        val triggerAt = maxOf(timeMs, System.currentTimeMillis() + 1_000L)
        setExactAlarm(record.id, triggerAt)
        Log.d(TAG, "scheduled pinId=${record.id} at=$triggerAt attemptsRemaining=${record.attemptsRemaining}")
    }

    /**
     * Cancel a pending alarm by pin id. Safe to call when no alarm is
     * scheduled (the PendingIntent lookup returns null and we no-op). Does
     * NOT touch [PinStore] or the watch timeline pin — those belong to the
     * caller that owns the broader cancel flow.
     */
    fun cancel(pinId: String) {
        val pi = firePendingIntent(pinId, flagsForLookup()) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
        notificationManager.cancel(notificationId(pinId))
        Log.d(TAG, "cancelled pinId=$pinId")
    }

    /**
     * Boot-time recovery. Walks every record in [PinStore] and re-arms any
     * persistent reminder that still has attempts left. A record whose
     * [timeMs] has already passed reschedules a fire shortly after boot so
     * the user catches up on what they missed while powered down.
     */
    fun restoreAll() {
        val now = System.currentTimeMillis()
        val live = pinStore.all().filter { it.isPersistent && it.attemptsRemaining > 0 }
        live.forEach { record ->
            val triggerAt = maxOf(record.timeMs ?: now, now + RESTORE_GRACE_MS)
            setExactAlarm(record.id, triggerAt)
        }
        Log.d(TAG, "restoreAll: re-armed ${live.size} persistent record(s)")
    }

    // --- internals ---

    private fun setExactAlarm(pinId: String, triggerAtMs: Long) {
        val pi = firePendingIntent(pinId, flagsForCreate()) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            // User revoked SCHEDULE_EXACT_ALARM at runtime — fall back to
            // inexact so the reminder still lands, just possibly delayed
            // by Doze. Better late than silent.
            Log.w(TAG, "exact alarm not permitted — falling back to inexact for $pinId")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    /**
     * Build the PendingIntent that fires the next nag. `IMMUTABLE` is
     * required on Android 12+; `UPDATE_CURRENT` lets a re-schedule pick up
     * the latest extras without leaking duplicate alarms.
     */
    private fun firePendingIntent(pinId: String, flags: Int): PendingIntent? {
        val intent = Intent(app, PersistentReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_PIN_ID, pinId)
        }
        return PendingIntent.getBroadcast(app, requestCode(pinId), intent, flags)
    }

    companion object {
        const val CHANNEL_ID = "persistent_reminders"

        const val ACTION_FIRE = "com.lazydevs.wristotle.PERSISTENT_REMINDER_FIRE"
        const val ACTION_STOP = "com.lazydevs.wristotle.PERSISTENT_REMINDER_STOP"
        const val EXTRA_PIN_ID = "pin_id"

        /** Grace period after boot before the first catch-up fire — gives
         *  the rest of the app time to come up before nagging. */
        private const val RESTORE_GRACE_MS = 30_000L

        /** Stable per-record id for both the AlarmManager request code AND
         *  the notification id, so a re-arm cancels the prior heads-up
         *  cleanly. Hash is plenty — collisions just stomp each other,
         *  which is bounded by [PinStore]'s MAX_PINS = 10. */
        fun requestCode(pinId: String): Int = pinId.hashCode()
        fun notificationId(pinId: String): Int = pinId.hashCode()

        /** Re-fire cadence — reads `ReminderSettings.defaultIntervalMin`
         *  freshly each call so a Settings edit takes effect on the very
         *  next re-arm without restarting the app. Cheap (single SharedPrefs
         *  read), called once per fire. */
        fun intervalMs(context: Context): Long =
            ReminderSettings(context).defaultIntervalMin.value * 60_000L

        private fun flagsForCreate(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun flagsForLookup(): Int =
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    }
}
