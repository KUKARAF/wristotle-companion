// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers.persistent

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.handlers.PinStore
import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.speech.nlu.notifier.PersistentReminderNagFormatter

private const val TAG = "PersistentReminder"

/**
 * Receives the two internal broadcasts that drive persistent reminders:
 *
 *  - [PersistentReminderScheduler.ACTION_FIRE] — AlarmManager fired; post the
 *    notification, decrement [ReminderRecord.attemptsRemaining], save back to
 *    [PinStore], and re-arm the next nag if attempts remain.
 *  - [PersistentReminderScheduler.ACTION_STOP] — user tapped Stop on a
 *    posted notification. Cancel the pending alarm, dismiss the active
 *    notification, and remove the record from [PinStore]. The watch timeline
 *    pin has already fired by the time Stop is reachable, so no transport
 *    work is needed here.
 *
 * Boot recovery lives in [PersistentReminderBootReceiver] because
 * `BOOT_COMPLETED` requires `android:exported="true"` and we don't want the
 * fire/stop entry points exposed externally.
 *
 * The receiver re-instantiates the scheduler on each invocation (it's a thin
 * holder of system service handles) rather than reaching into the
 * Application singleton — keeps the receiver self-contained and avoids a
 * window where the Application hasn't yet bound its lazy field.
 */
class PersistentReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pinId = intent.getStringExtra(PersistentReminderScheduler.EXTRA_PIN_ID)
            ?: return
        when (intent.action) {
            PersistentReminderScheduler.ACTION_FIRE -> handleFire(context, pinId)
            PersistentReminderScheduler.ACTION_STOP -> handleStop(context, pinId)
        }
    }

    private fun handleFire(context: Context, pinId: String) {
        val pinStore = PinStore(context)
        val record = pinStore.all().firstOrNull { it.id == pinId } ?: run {
            // The record was cancelled (voice or watch-side) between the
            // alarm being armed and now. No-op — the alarm itself is
            // already consumed by AlarmManager.
            Log.d(TAG, "fire: pinId=$pinId not in store, ignoring")
            return
        }
        if (!record.isPersistent || record.attemptsRemaining <= 0) {
            Log.d(TAG, "fire: pinId=$pinId no longer eligible (persistent=${record.isPersistent} remaining=${record.attemptsRemaining})")
            return
        }

        postNotification(context, record)

        val next = record.copy(attemptsRemaining = record.attemptsRemaining - 1)
        pinStore.save(next)
        if (next.attemptsRemaining > 0) {
            val triggerAt = System.currentTimeMillis() +
                PersistentReminderScheduler.intervalMs(context)
            armNext(context, pinId, triggerAt)
            Log.d(TAG, "fire: pinId=$pinId re-armed at=$triggerAt remaining=${next.attemptsRemaining}")
        } else {
            Log.d(TAG, "fire: pinId=$pinId exhausted attempts")
        }
    }

    private fun handleStop(context: Context, pinId: String) {
        PersistentReminderScheduler(context.applicationContext).cancel(pinId)
        PinStore(context).remove(pinId)
        Log.d(TAG, "stop: pinId=$pinId cleared")
    }

    private fun postNotification(context: Context, record: ReminderRecord) {
        val notifId = PersistentReminderScheduler.notificationId(record.id)
        val requestCode = PersistentReminderScheduler.requestCode(record.id)
        val stopIntent = Intent(context, PersistentReminderReceiver::class.java).apply {
            action = PersistentReminderScheduler.ACTION_STOP
            putExtra(PersistentReminderScheduler.EXTRA_PIN_ID, record.id)
        }
        val stopPi = PendingIntent.getBroadcast(
            context,
            // Stop and Fire share the same request code per pinId — they
            // live under distinct action strings so they don't collide, and
            // we want every Wristotle artefact for a logical reminder to
            // resolve from the same hash.
            requestCode,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Tap on the notification body → open the launcher activity so the
        // user can take action (list/cancel/edit). Without a contentIntent
        // tapping does nothing visible, which the smoke-test caught.
        val contentPi = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.let {
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val notification = NotificationCompat.Builder(context, PersistentReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(record.title)
            .setContentText(PersistentReminderNagFormatter.body(record.attemptsRemaining))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Auto-cancel only on tap (opens the app). The Stop action
            // dismisses explicitly; a swipe-away dismisses just this nag
            // but leaves the chain alive so the next one still fires —
            // matches the "persistent" promise.
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPi,
                ).build(),
            )
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(notifId, notification)
    }

    private fun armNext(context: Context, pinId: String, triggerAtMs: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val intent = Intent(app, PersistentReminderReceiver::class.java).apply {
            action = PersistentReminderScheduler.ACTION_FIRE
            putExtra(PersistentReminderScheduler.EXTRA_PIN_ID, pinId)
        }
        val pi = PendingIntent.getBroadcast(
            app,
            PersistentReminderScheduler.requestCode(pinId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !am.canScheduleExactAlarms()
        ) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }
}