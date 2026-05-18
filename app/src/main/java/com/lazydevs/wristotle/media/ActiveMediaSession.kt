package com.lazydevs.wristotle.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

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

    /**
     * Dispatch a raw MEDIA_PLAY system key event without consulting
     * the active-session list. Last-resort fallback when targeted
     * playback isn't possible — the system picks the recipient, which
     * is usually the *previously*-active media app, not the one we
     * just launched.
     */
    fun playKeyEvent(): Boolean = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    /**
     * Start playback in [packageId] specifically — used right after
     * [com.lazydevs.wristotle.handlers.MediaPlayHandler] launches an
     * app, so the request reaches the just-launched app and not
     * whatever was previously playing.
     *
     * Three-tier strategy:
     *   1. Quick session poll for apps that activate fast (Spotify-
     *      style: session is registered and `active=true` within a few
     *      hundred ms of launch).
     *   2. Targeted MEDIA_BUTTON broadcast to the package's
     *      MediaButtonReceiver. Works for apps whose session is
     *      registered but inactive — `getActiveSessions` filters those
     *      out (Flutter `audio_service` style: session exists but
     *      `active=false` until first user play tap). The receiver
     *      activates the session and starts playback, all triggered
     *      by a single Intent we deliver directly to the right
     *      component.
     *   3. System-wide MEDIA_PLAY key event. Last resort — usually
     *      routes to the *previously*-active session, not the one we
     *      just launched, but it's all we have if the app didn't
     *      register a media button receiver either.
     */
    suspend fun playForPackage(packageId: String): Boolean {
        // Tier 1: quick poll. Two attempts × 250 ms = 500 ms ceiling so
        // we don't make the user wait when targeted broadcast (tier 2)
        // is going to fire anyway.
        repeat(2) { attempt ->
            delay(250L)
            val controller = findControllerFor(packageId)
            if (controller != null) {
                controller.transportControls.play()
                Log.d(TAG, "tier 1 (session play) on $packageId after ${(attempt + 1) * 250}ms")
                return true
            }
        }
        // Tier 2: targeted MEDIA_BUTTON broadcast.
        if (sendTargetedMediaButton(packageId, KeyEvent.KEYCODE_MEDIA_PLAY)) {
            Log.d(TAG, "tier 2 (targeted MEDIA_BUTTON broadcast) on $packageId")
            return true
        }
        // Tier 3: system-wide key event.
        Log.w(TAG, "tiers 1+2 failed for $packageId — falling back to system key event")
        return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun findControllerFor(packageId: String): MediaController? {
        val sessions = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification access — can't search for $packageId session", e)
            return null
        }
        return sessions.firstOrNull { it.packageName == packageId }
    }

    /**
     * Send a MEDIA_BUTTON broadcast intent directly to [packageId]'s
     * registered MediaButtonReceiver. This bypasses system-wide audio
     * routing (which would deliver to the most-recently-active media
     * app, usually the *previous* one), reaching the just-launched
     * app even when its session is registered but inactive.
     *
     * Returns false when the package doesn't expose a media button
     * receiver — caller should fall through to the next tier.
     */
    private fun sendTargetedMediaButton(packageId: String, keyCode: Int): Boolean {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageId)
        // queryBroadcastReceivers requires QUERY_ALL_PACKAGES on Android
        // 11+ to return cross-package matches — which we already hold
        // for the AppIndex feature.
        val receivers = try {
            pm.queryBroadcastReceivers(probe, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "queryBroadcastReceivers($packageId) failed", t)
            return false
        }
        val info = receivers.firstOrNull()?.activityInfo ?: return false
        val component = ComponentName(info.packageName, info.name)
        return try {
            // The receiver expects a DOWN then UP. Some receivers only
            // fire on UP; some on either. Send both, like the system
            // does when a hardware media key is pressed.
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(component)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            )
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(component)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendBroadcast(MEDIA_BUTTON → ${component.flattenToString()}) failed", t)
            false
        }
    }

    fun pause(): Boolean {
        val controller = targetSession() ?: return false
        controller.transportControls.pause()
        return true
    }

    /**
     * Pause / next / previous targeted at a specific [packageId].
     * Uses the same two-tier fallback as [playForPackage]:
     *   1. If we can see the app's MediaController in
     *      `getActiveSessions`, call transportControls directly.
     *   2. Otherwise (common for Flutter audio_service apps that
     *      register a session but keep `active=false`), send a
     *      targeted MEDIA_BUTTON broadcast directly to the package's
     *      receiver — the receiver activates the session and applies
     *      the command.
     * Skips the system-wide key event last-resort that [playForPackage]
     * uses, because dispatching a global KEYCODE_MEDIA_PAUSE / NEXT /
     * PREVIOUS would land on the wrong app (the previously-active
     * session, which is exactly the bug this whole branch fixes).
     * Returns false when neither path is available.
     */
    fun pausePackage(packageId: String): Boolean =
        commandPackage(packageId, KeyEvent.KEYCODE_MEDIA_PAUSE) {
            it.transportControls.pause()
        }

    fun nextPackage(packageId: String): Boolean =
        commandPackage(packageId, KeyEvent.KEYCODE_MEDIA_NEXT) {
            it.transportControls.skipToNext()
        }

    fun previousPackage(packageId: String): Boolean =
        commandPackage(packageId, KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            it.transportControls.skipToPrevious()
        }

    private inline fun commandPackage(
        packageId: String,
        broadcastKeyCode: Int,
        sessionAction: (MediaController) -> Unit,
    ): Boolean {
        findControllerFor(packageId)?.let {
            sessionAction(it)
            Log.d(TAG, "session command $broadcastKeyCode on $packageId")
            return true
        }
        if (sendTargetedMediaButton(packageId, broadcastKeyCode)) {
            Log.d(TAG, "targeted MEDIA_BUTTON $broadcastKeyCode on $packageId")
            return true
        }
        Log.w(TAG, "no session and no media button receiver for $packageId")
        return false
    }

    /** Resolved app label for [packageId], for the response string. */
    fun appLabel(packageId: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageId, 0)).toString()
    } catch (t: Throwable) {
        packageId
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

    /**
     * Pause every currently-playing media session EXCEPT [keepPackage].
     * Used right before [com.lazydevs.wristotle.handlers.MediaPlayHandler]
     * launches a different app so the two apps don't both play audio
     * at the same time — Android's audio-focus negotiation alone can't
     * be relied on when the new activity launches off-screen (phone
     * locked) and never gains visible foreground.
     */
    fun pauseOthers(keepPackage: String) {
        val sessions = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification access — can't enumerate sessions to pause", e)
            return
        }
        for (ctrl in sessions) {
            if (ctrl.packageName == keepPackage) continue
            val state = ctrl.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                Log.d(TAG, "pausing ${ctrl.packageName} (state=$state) before launching $keepPackage")
                ctrl.transportControls.pause()
            }
        }
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
