package com.lazydevs.wristotle.media

import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

private const val TAG = "ActiveMediaSession"

/**
 * Thin wrapper over the Android `MediaSessionManager` system service that
 * picks the "right" media session to act on and exposes the transport
 * commands as direct method calls.
 *
 * Single-direction: we only send commands; we don't bridge state back to
 * the watch (the dictation-response path already echoes a confirmation).
 * That's what lets us skip the elaborate per-session-callback machinery
 * `libpebble3/AndroidSystemMusicControl.kt` needs for two-way state sync;
 * we just snapshot the active sessions on demand.
 *
 * Permission gate: every method here is a no-op if
 * `MediaSessionsListener` isn't yet enabled via Notification Access — the
 * Android API throws `SecurityException` and we swallow it. The
 * `Permissions` UI surfaces the state and the "Open Settings" action for
 * the user.
 */
class ActiveMediaSession(private val context: Context) {

    private val mediaSessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listenerComponent = MediaSessionsListener.componentName(context)

    /** True iff the user has granted Notification Access to this app. */
    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Returns the media session most likely to be the user's intent
     * target: an actively-playing one wins; otherwise a buffering one;
     * otherwise the first session in the list. Null when no sessions
     * are active or the permission gate isn't open.
     */
    private fun targetSession(): MediaController? {
        val sessions = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification access — can't enumerate media sessions", e)
            return null
        }
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: sessions.first()
    }

    /** Display name of the app whose session we'd act on, for the user-facing
     *  response. Falls back to the package id when we can't resolve a label. */
    fun activeAppName(): String? {
        val session = targetSession() ?: return null
        val pkg = session.packageName ?: return null
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }
    }

    fun play(): Boolean {
        targetSession()?.transportControls?.play()?.also { return true }
        // No session at all — fall back to the system media-key event,
        // which wakes up the last-played app on some devices/skins.
        // libpebble3 does the same. Don't fall through for pause/skip
        // because those should be no-ops when nothing is playing.
        return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause(): Boolean {
        val controller = targetSession() ?: return false
        controller.transportControls.pause()
        return true
    }

    fun playPause(): Boolean {
        val controller = targetSession() ?: return play()
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) controller.transportControls.pause()
        else controller.transportControls.play()
        return true
    }

    fun next(): Boolean {
        val controller = targetSession() ?: return false
        controller.transportControls.skipToNext()
        return true
    }

    fun previous(): Boolean {
        val controller = targetSession() ?: return false
        controller.transportControls.skipToPrevious()
        return true
    }

    /** Seek by [deltaSeconds] (positive = forward, negative = backward).
     *  Reads the current position, clamps to non-negative, calls seekTo. */
    fun seekBy(deltaSeconds: Int): Boolean {
        val controller = targetSession() ?: return false
        val state = controller.playbackState ?: return false
        val newPosMs = (state.position + deltaSeconds * 1000L).coerceAtLeast(0L)
        controller.transportControls.seekTo(newPosMs)
        return true
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        return runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrElse {
            Log.w(TAG, "dispatchMediaKey($keyCode) failed", it)
            false
        }
    }
}
