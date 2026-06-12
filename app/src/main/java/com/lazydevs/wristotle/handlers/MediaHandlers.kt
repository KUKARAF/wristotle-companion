// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import android.content.Context
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.launchApp
import com.lazydevs.wristotle.apps.packageLabel
import com.lazydevs.wristotle.media.ActiveMediaSession
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.intSlot

/**
 * Handlers for the `Intent.Media*` variants, grouped in one file
 * because each is short and they share a common shape:
 *   1. permission gate (Notification Access)
 *   2. resolve target app from the `app` slot (via [AppTarget])
 *      — Specific package → targeted command on that package
 *      — Fallback         → command on whichever session is active
 *      — NotFound         → refuse to silently substitute
 *
 * MediaPlay is the only handler that *launches* an app (the others
 * only command apps that are already running).
 */

private const val NEEDS_PERMISSION =
    "Notification Access not granted — enable it in the Permissions tab so I can control media."
private const val NOTHING_PLAYING = "Nothing is playing"
private const val notFoundPrefix = "Couldn't find an app called"

private fun ActiveMediaSession.targetLabel(): String = activeAppName() ?: "media"

private fun notFound(spoken: String): String = "$notFoundPrefix $spoken"

class MediaPlayHandler(
    private val context: Context,
    private val media: ActiveMediaSession,
    private val appIndex: AppIndex,
) : ActionHandler {
    override val tag = TAG
    override val intent = Intent.MediaPlay

    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return when (val target = result.resolveAppTarget(appIndex)) {
            is AppTarget.Specific -> launchAndPlay(target.packageId)
            is AppTarget.NotFound -> notFound(target.spoken)
            AppTarget.EmptyIndex -> EMPTY_INDEX_HINT
            AppTarget.Fallback ->
                if (media.play()) "Playing ${media.targetLabel()}" else NOTHING_PLAYING
        }
    }

    private suspend fun launchAndPlay(packageId: String): String {
        // Pause whatever is currently playing BEFORE launching — with the
        // screen off, Android's audio focus alone won't reliably tell
        // the previous app to back off (the new activity launches
        // without visible foreground).
        media.pauseOthers(keepPackage = packageId)
        if (!launchApp(context, packageId)) return "Couldn't launch ${packageLabel(context, packageId)}"
        media.playForPackage(packageId)
        return "Playing ${packageLabel(context, packageId)}"
    }

    companion object {
        /** Persisted in ConversationEntry.handler. Referenced from the
         *  Conversation chip-routing code in `ui/` — keep in sync if
         *  you ever rename the tag (would require a data migration). */
        const val TAG = "media.play"
    }
}

class MediaPauseHandler(
    private val context: Context,
    private val media: ActiveMediaSession,
    private val appIndex: AppIndex,
) : ActionHandler {
    override val tag = "media.pause"
    override val intent = Intent.MediaPause
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return when (val target = result.resolveAppTarget(appIndex)) {
            is AppTarget.Specific ->
                if (media.pauseForPackage(target.packageId)) "Paused ${packageLabel(context, target.packageId)}"
                else NOTHING_PLAYING
            is AppTarget.NotFound -> notFound(target.spoken)
            AppTarget.EmptyIndex -> EMPTY_INDEX_HINT
            AppTarget.Fallback ->
                if (media.pause()) "Paused ${media.targetLabel()}" else NOTHING_PLAYING
        }
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

class MediaNextHandler(
    private val context: Context,
    private val media: ActiveMediaSession,
    private val appIndex: AppIndex,
) : ActionHandler {
    override val tag = "media.next"
    override val intent = Intent.MediaNext
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return when (val target = result.resolveAppTarget(appIndex)) {
            is AppTarget.Specific ->
                if (media.nextForPackage(target.packageId)) "Skipped to next in ${packageLabel(context, target.packageId)}"
                else NOTHING_PLAYING
            is AppTarget.NotFound -> notFound(target.spoken)
            AppTarget.EmptyIndex -> EMPTY_INDEX_HINT
            AppTarget.Fallback ->
                if (media.next()) "Skipped to next" else NOTHING_PLAYING
        }
    }
}

class MediaPreviousHandler(
    private val context: Context,
    private val media: ActiveMediaSession,
    private val appIndex: AppIndex,
) : ActionHandler {
    override val tag = "media.previous"
    override val intent = Intent.MediaPrevious
    override suspend fun handle(result: IntentResult): String {
        if (!media.hasNotificationAccess()) return NEEDS_PERMISSION
        return when (val target = result.resolveAppTarget(appIndex)) {
            is AppTarget.Specific ->
                if (media.previousForPackage(target.packageId)) "Back one track in ${packageLabel(context, target.packageId)}"
                else NOTHING_PLAYING
            is AppTarget.NotFound -> notFound(target.spoken)
            AppTarget.EmptyIndex -> EMPTY_INDEX_HINT
            AppTarget.Fallback ->
                if (media.previous()) "Back one track" else NOTHING_PLAYING
        }
    }
}

/**
 * Both `Intent.MediaSeekForward` and `Intent.MediaSeekBackward` use this
 * handler — direction comes from the intent, magnitude from the optional
 * `seconds` slot ([com.lazydevs.wristotle.speech.nlu.slots.MediaSeekSlots]).
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
        val seconds = result.intSlot(SlotKeys.Seconds) ?: defaultSeconds()
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