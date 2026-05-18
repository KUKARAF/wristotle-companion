package com.lazydevs.wristotle.handlers

import android.content.Context
import android.content.Intent as AndroidIntent
import android.util.Log
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.media.ActiveMediaSession
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult

/**
 * Handlers for the seven `Intent.Media*` variants, all backed by a
 * single shared [ActiveMediaSession]. Grouped in one file because each
 * is ~10 lines and they share the same prologue (check the permission
 * gate, look up the active app for the response string).
 *
 * The user-facing response string always mentions the app being
 * controlled when one is identifiable, so the watch chat reads
 * something useful like "Paused Spotify" instead of just "Paused".
 *
 * No-op semantics when nothing is playing:
 *   • Play: falls through to the system MEDIA_PLAY key event in
 *     [ActiveMediaSession.play] so paused-but-loaded apps still resume.
 *   • Everything else: returns "Nothing is playing" so the user knows
 *     why the watch didn't do anything.
 */

private const val NEEDS_PERMISSION =
    "Notification Access not granted — enable it in the Permissions tab so I can control media."
private const val NOTHING_PLAYING = "Nothing is playing"

private fun ActiveMediaSession.targetLabel(): String = activeAppName() ?: "media"

/**
 * MediaPlay is body-aware: when the user said "play <app>" and that
 * name resolves through [AppIndex], we launch that app and then poke
 * the system MEDIA_PLAY key event after a short settle delay so the
 * just-launched app receives focus and resumes. The handler also
 * still works for the bare "play" / "resume" case by falling through
 * to [ActiveMediaSession.play] — that delegates to whichever session
 * is most recently active.
 *
 * The launch-app branch needs `Context` (to call
 * `startActivity`/`packageManager`) and `AppIndex` (to resolve the
 * spoken name). Both are Application-scoped so this is cheap.
 */
class MediaPlayHandler(
    private val context: Context,
    private val media: ActiveMediaSession,
    private val appIndex: AppIndex,
) : ActionHandler {
    override val tag = "media.play"
    override val intent = Intent.MediaPlay

    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        val appQuery = (result.slots["app"] as? String)?.trim()
        if (!appQuery.isNullOrEmpty()) {
            val pkg = appIndex.find(appQuery)
            if (pkg != null) {
                return launchAndPlay(pkg, appQuery)
            }
            // Body present but didn't resolve — fall through to active
            // session rather than erroring out. The user might have
            // said "play that podcast" / "play my favourites" — those
            // shouldn't fail just because we can't launch an app.
        }
        return if (media.play()) "Playing ${media.targetLabel()}" else NOTHING_PLAYING
    }

    private suspend fun launchAndPlay(packageId: String, spokenName: String): String {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageId)
        if (launchIntent == null) {
            Log.w("MediaPlayHandler", "no launch intent for $packageId — falling back to transport-play")
            return if (media.play()) "Playing ${media.targetLabel()}" else NOTHING_PLAYING
        }
        launchIntent.addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK)
        // Pause whatever is currently playing BEFORE launching the new
        // target. With the phone locked / screen off, Android's audio
        // focus negotiation won't reliably tell the previous app to
        // back off when the new activity launches without visible
        // foreground, so we ask the previous app to pause explicitly.
        media.pauseOthers(keepPackage = packageId)
        try {
            context.startActivity(launchIntent)
        } catch (t: Throwable) {
            Log.w("MediaPlayHandler", "startActivity($packageId) failed", t)
            return "Couldn't launch $spokenName"
        }
        // Target the just-launched app's MediaController directly
        // instead of broadcasting a system key event. The previous
        // approach routed MEDIA_PLAY to whichever app was "most
        // recently active" in the audio system — usually the *previous*
        // app, not the one we just launched. playForPackage polls for
        // the new session to register (Flutter audio_service apps like
        // Absorb publish lazily, ~500–1500 ms after launch) and then
        // calls transportControls.play on it directly.
        media.playForPackage(packageId)
        // Don't echo the package id back to the user; the spoken name
        // is what they said and what they expect to hear.
        return "Playing $spokenName"
    }
}

class MediaPauseHandler(private val media: ActiveMediaSession) : ActionHandler {
    override val tag = "media.pause"
    override val intent = Intent.MediaPause
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return if (media.pause()) "Paused ${media.targetLabel()}" else NOTHING_PLAYING
    }
}

class MediaPlayPauseHandler(private val media: ActiveMediaSession) : ActionHandler {
    override val tag = "media.toggle"
    override val intent = Intent.MediaPlayPause
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return if (media.playPause()) "Toggled ${media.targetLabel()}" else NOTHING_PLAYING
    }
}

class MediaNextHandler(private val media: ActiveMediaSession) : ActionHandler {
    override val tag = "media.next"
    override val intent = Intent.MediaNext
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return if (media.next()) "Skipped to next" else NOTHING_PLAYING
    }
}

class MediaPreviousHandler(private val media: ActiveMediaSession) : ActionHandler {
    override val tag = "media.previous"
    override val intent = Intent.MediaPrevious
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return if (media.previous()) "Back one track" else NOTHING_PLAYING
    }
}

/**
 * Both `Intent.MediaSeekForward` and `Intent.MediaSeekBackward` use this
 * handler. Direction comes from the intent (forward = +, backward = -);
 * magnitude is the optional `seconds` slot (`MediaSeekSlots`), defaulting
 * to 30 forward / 10 back when the user didn't say a number.
 */
class MediaSeekHandler(
    private val media: ActiveMediaSession,
    override val intent: Intent,
) : ActionHandler {
    override val tag: String = when (intent) {
        Intent.MediaSeekForward -> "media.seek.forward"
        Intent.MediaSeekBackward -> "media.seek.backward"
        else -> error("MediaSeekHandler only handles MediaSeek{Forward,Backward}; got $intent")
    }

    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        val seconds = (result.slots["seconds"] as? Int) ?: defaultSeconds()
        val delta = if (intent == Intent.MediaSeekForward) seconds else -seconds
        if (!media.seekBy(delta)) return NOTHING_PLAYING
        val verb = if (delta > 0) "Forward" else "Back"
        val absS = kotlin.math.abs(delta)
        return "$verb ${absS}s in ${media.targetLabel()}"
    }

    private fun defaultSeconds(): Int =
        if (intent == Intent.MediaSeekForward) DEFAULT_FORWARD_S else DEFAULT_BACK_S

    private companion object {
        const val DEFAULT_FORWARD_S = 30
        const val DEFAULT_BACK_S = 10
    }
}
