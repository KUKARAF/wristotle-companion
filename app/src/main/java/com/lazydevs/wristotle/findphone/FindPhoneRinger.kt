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

    /** Start (or restart) the ring. Safe to call repeatedly. */
    fun start(context: Context) {
        val app = context.applicationContext
        main.post {
            releasePlayer()
            ensureChannel(app)
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
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            Log.d(TAG, "stopped")
        }
    }

    private fun releasePlayer() {
        player?.let { p -> runCatching { if (p.isPlaying) p.stop() }; runCatching { p.release() } }
        player = null
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
        val stopPi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FindPhoneReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPi = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Finding your phone")
            .setContentText("Ringing on the alarm volume. Tap Stop when you've found it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
    }
}
