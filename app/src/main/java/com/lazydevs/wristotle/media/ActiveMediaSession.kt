package com.lazydevs.wristotle.media

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
import com.lazydevs.wristotle.apps.packageLabel
import kotlinx.coroutines.delay

private const val TAG = "ActiveMediaSession"

// Tier 1 of cross-app play: poll for the just-launched app's session
// to appear. Two attempts × 250 ms = 500 ms ceiling — short so we get
// to the targeted broadcast (tier 2) quickly when the app is one of
// the lazy-registration variety (Flutter audio_service, etc.).
private const val SESSION_POLL_ATTEMPTS = 2
private const val SESSION_POLL_DELAY_MS = 250L

/**
 * Facade over `MediaSessionManager` exposing two flavours of command:
 *
 *   - **Active-session ops** (`play`, `pause`, `next`, `previous`,
 *     `seekBy`, `playPause`) act on whichever session is currently
 *     active. Used when the user said just "play" / "pause".
 *   - **Per-package ops** (`playForPackage`, `pauseForPackage`,
 *     `nextForPackage`, `previousForPackage`, `pauseOthers`) target a
 *     specific app. Used when the user said "play <app>" / "pause
 *     <app>" — required because Android's audio routing otherwise
 *     delivers media-key events to the *previously* active app, not
 *     the one the user named.
 *
 * Both rely on Notification Access being granted; every method here
 * is a no-op (returns false / null) when it isn't. The Permissions
 * UI surfaces the state.
 *
 * Single-direction: commands only, no state stream back to the watch.
 * That's what lets us skip the per-session-callback bookkeeping
 * `libpebble3/AndroidSystemMusicControl.kt` needs for two-way sync.
 */
class ActiveMediaSession(private val context: Context) {

    private val mediaSessionManager: MediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listenerComponent = MediaSessionsListener.componentName(context)

    // ── Permission gate ────────────────────────────────────────────

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    // ── Active-session ops ─────────────────────────────────────────

    fun play(): Boolean {
        targetSession()?.transportControls?.play()?.also { return true }
        // No session at all — fall back to the system media-key event,
        // which wakes up the last-played app on some devices/skins.
        // Don't do this for pause/skip — those should no-op when
        // there's nothing playing.
        return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pause(): Boolean = targetSession()?.also { it.transportControls.pause() } != null

    fun next(): Boolean = targetSession()?.also { it.transportControls.skipToNext() } != null

    fun previous(): Boolean = targetSession()?.also { it.transportControls.skipToPrevious() } != null

    fun playPause(): Boolean {
        val controller = targetSession() ?: return play()
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) controller.transportControls.pause()
        else controller.transportControls.play()
        return true
    }

    /** Seek by [deltaSeconds] (positive = forward, negative = backward).
     *  Clamps position to non-negative. */
    fun seekBy(deltaSeconds: Int): Boolean {
        val controller = targetSession() ?: return false
        val state = controller.playbackState ?: return false
        val newPosMs = (state.position + deltaSeconds * 1000L).coerceAtLeast(0L)
        controller.transportControls.seekTo(newPosMs)
        return true
    }

    /** Display name of the app whose session we'd act on, for the
     *  response string. Null when no session is available. */
    fun activeAppName(): String? =
        targetSession()?.packageName?.let { packageLabel(context, it) }

    // ── Per-package ops ────────────────────────────────────────────

    /**
     * Start playback in [packageId] specifically. Three-tier fallback:
     *   1. Quick poll for the app's MediaController to register
     *      (Spotify-style: session goes active within ~250 ms).
     *   2. Targeted MEDIA_BUTTON broadcast to the package's
     *      MediaButtonReceiver (Flutter audio_service-style: session
     *      registered but inactive, so `getActiveSessions` hides it).
     *   3. System-wide MEDIA_PLAY key event — usually lands on the
     *      previously-active app, but it's all we have left.
     */
    suspend fun playForPackage(packageId: String): Boolean {
        repeat(SESSION_POLL_ATTEMPTS) { attempt ->
            delay(SESSION_POLL_DELAY_MS)
            findControllerFor(packageId)?.let {
                it.transportControls.play()
                Log.d(TAG, "tier 1 session-play on $packageId after ${(attempt + 1) * SESSION_POLL_DELAY_MS}ms")
                return true
            }
        }
        if (sendTargetedMediaButton(packageId, KeyEvent.KEYCODE_MEDIA_PLAY)) {
            Log.d(TAG, "tier 2 targeted MEDIA_BUTTON on $packageId")
            return true
        }
        Log.w(TAG, "tiers 1+2 failed for $packageId — falling back to system key event")
        return dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    /**
     * Pause / next / previous targeted at a specific [packageId].
     * Two-tier fallback (session command → targeted MEDIA_BUTTON);
     * skips the system-wide key event last resort that
     * [playForPackage] uses — dispatching a global pause/next/prev
     * would land on the previously-active session, which is exactly
     * the bug per-package targeting exists to fix.
     */
    fun pauseForPackage(packageId: String): Boolean =
        commandPackage(packageId, KeyEvent.KEYCODE_MEDIA_PAUSE) { it.transportControls.pause() }

    fun nextForPackage(packageId: String): Boolean =
        commandPackage(packageId, KeyEvent.KEYCODE_MEDIA_NEXT) { it.transportControls.skipToNext() }

    fun previousForPackage(packageId: String): Boolean =
        commandPackage(packageId, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.transportControls.skipToPrevious() }

    /**
     * Pause every currently-playing session EXCEPT [keepPackage].
     * Called right before launching a different app so audio from
     * the previous one doesn't keep playing alongside — Android's
     * audio focus alone can't be relied on when the new activity
     * launches off-screen (phone locked, no visible foreground).
     */
    fun pauseOthers(keepPackage: String) {
        for (ctrl in activeSessions()) {
            if (ctrl.packageName == keepPackage) continue
            val state = ctrl.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                Log.d(TAG, "pausing ${ctrl.packageName} (state=$state) before launching $keepPackage")
                ctrl.transportControls.pause()
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────

    /** Most-likely user-intent session: playing > buffering > first.
     *  Null when there are no active sessions or notification access
     *  hasn't been granted. */
    private fun targetSession(): MediaController? {
        val sessions = activeSessions()
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_BUFFERING }
            ?: sessions.first()
    }

    private fun findControllerFor(packageId: String): MediaController? =
        activeSessions().firstOrNull { it.packageName == packageId }

    /** Single point of entry for `getActiveSessions` — every caller
     *  hits the same SecurityException handling and the same log
     *  message when notification access isn't granted. */
    private fun activeSessions(): List<MediaController> = try {
        mediaSessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        Log.w(TAG, "no notification access — getActiveSessions denied", e)
        emptyList()
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

    /**
     * Send a MEDIA_BUTTON broadcast directly to [packageId]'s
     * registered MediaButtonReceiver — bypasses system-wide audio
     * routing entirely. False when the package doesn't expose such a
     * receiver. Requires QUERY_ALL_PACKAGES on Android 11+ (already
     * held for the AppIndex feature).
     */
    private fun sendTargetedMediaButton(packageId: String, keyCode: Int): Boolean {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageId)
        val receivers = try {
            pm.queryBroadcastReceivers(probe, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "queryBroadcastReceivers($packageId) failed", t)
            return false
        }
        val info = receivers.firstOrNull()?.activityInfo ?: return false
        val component = ComponentName(info.packageName, info.name)
        return try {
            // DOWN then UP — receivers vary on which they listen for;
            // mimic a hardware media-key press by sending both.
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

    private fun dispatchMediaKey(keyCode: Int): Boolean = runCatching {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        true
    }.getOrElse {
        Log.w(TAG, "dispatchMediaKey($keyCode) failed", it)
        false
    }
}
