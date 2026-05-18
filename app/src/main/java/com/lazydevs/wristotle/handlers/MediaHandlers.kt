package com.lazydevs.wristotle.handlers

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

class MediaPlayHandler(private val media: ActiveMediaSession) : ActionHandler {
    override val tag = "media.play"
    override val intent = Intent.MediaPlay
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return if (media.play()) "Playing ${media.targetLabel()}" else NOTHING_PLAYING
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
