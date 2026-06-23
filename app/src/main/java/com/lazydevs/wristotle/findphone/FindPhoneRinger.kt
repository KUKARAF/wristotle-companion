// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.findphone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.logging.WristotleLog as Log

/**
 * Rings the phone for "find my phone" on the **alarm** stream
 * ([AudioAttributes.USAGE_ALARM]) — so it's audible even when the media volume
 * is zero, which the Web-Audio (PKJS) ringer couldn't do (Web Audio always
 * routes through media). Plays the device's default ringtone, looping, and
 * auto-stops after [MAX_DURATION_MS]; a heads-up notification with a **Stop**
 * action stops it sooner.
 *
 * Singleton so the [FindPhoneReceiver] Stop broadcast can reach the live
 * [MediaPlayer]. All access is on the main thread.
 */
object FindPhoneRinger {

    const val ACTION_STOP = "com.lazydevs.wristotle.FIND_PHONE_STOP"
    const val CHANNEL_ID = "find_phone"
    private const val NOTIF_ID = 4317
    private const val MAX_DURATION_MS = 30_000L
    private const val TAG = "FindPhoneRinger"

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    /** Alarm-stream volume captured before we boosted it, restored on stop. */
    private var savedAlarmVolume: Int? = null

    /** Start (or restart) the ring. Safe to call repeatedly. */
    fun start(context: Context) {
        val app = context.applicationContext
        main.post {
            releasePlayer()
            ensureChannel(app)
            boostAlarmVolume(app)
            startVibration(app)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) {
                Log.w(TAG, "no default ringtone/alarm sound available")
                return@post
            }
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    stop(app); true
                }
                runCatching { setDataSource(app, uri); prepareAsync() }
                    .onFailure { Log.w(TAG, "setDataSource failed: ${it.message}"); stop(app) }
            }
            postNotification(app)
            main.postDelayed({ stop(app) }, MAX_DURATION_MS)
            Log.d(TAG, "ringing on the alarm stream")
        }
    }

    /** Stop the ring + clear the notification. */
    fun stop(context: Context) {
        val app = context.applicationContext
        main.post {
            main.removeCallbacksAndMessages(null)
            releasePlayer()
            stopVibration(app)
            restoreAlarmVolume(app)
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            Log.d(TAG, "stopped")
        }
    }

    private fun releasePlayer() {
        player?.let { p -> runCatching { if (p.isPlaying) p.stop() }; runCatching { p.release() } }
        player = null
    }

    /** Raise the alarm stream to max so find-my-phone is loud regardless of the
     *  user's alarm slider — the whole point is to actually locate the phone.
     *  Saves the prior level for [restoreAlarmVolume]. Best-effort: a
     *  SecurityException (e.g. Do Not Disturb without policy access) is ignored
     *  and we ring at the current level. */
    private fun boostAlarmVolume(context: Context) {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
            if (savedAlarmVolume == null) {
                savedAlarmVolume = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            }
            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, max, 0)
        }.onFailure { Log.w(TAG, "couldn't boost alarm volume: ${it.message}") }
    }

    private fun restoreAlarmVolume(context: Context) {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, saved, 0) }
            .onFailure { Log.w(TAG, "couldn't restore alarm volume: ${it.message}") }
    }

    /** Repeating vibration while ringing — helps locate a silenced / pocketed
     *  phone. Looped until [stopVibration]. */
    private fun startVibration(context: Context) {
        val v = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        val pattern = longArrayOf(0, 600, 400) // buzz 600ms, pause 400ms, repeat
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, 0)
            }
        }.onFailure { Log.w(TAG, "vibrate failed: ${it.message}") }
    }

    private fun stopVibration(context: Context) {
        runCatching { vibrator(context)?.cancel() }
    }

    private fun vibrator(context: Context): android.os.Vibrator? =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The ringer played when you trigger \"find my phone\" from your watch."
                setSound(null, null) // the MediaPlayer owns the sound; the notification stays silent
            },
        )
    }

    private fun postNotification(context: Context) {
        // Every way the user touches this notification stops the ring: the Stop
        // button, tapping the body (content intent), swiping it away (delete
        // intent) — all fire the same ACTION_STOP. Plus the 30s auto-timeout.
        // So the user can never end up with a ringing phone and no control.
        // (Our own NotificationManager.cancel() in stop() does NOT trigger the
        // delete intent, so there's no loop.)
        val stopPi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FindPhoneReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Finding your phone")
            .setContentText("Ringing on the alarm volume. Tap, Stop, or swipe away when you've found it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(stopPi)
            .setDeleteIntent(stopPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
    }
}
