// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui.components

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val TAG = "MediaPlayback"

/**
 * Single-instance audio playback controller scoped to the composition
 * that calls [rememberSingleMediaPlayer]. Both [ConversationScreen] and
 * [NotesScreen] used to roll their own MediaPlayer lifecycle inline,
 * with separate "currently playing id" state — easy place for one to
 * drift from the other.
 *
 * Contract:
 *  - [playingId] is the id (any String key) of the currently-playing
 *    clip, or null when idle.
 *  - [play] starts the given path under the given id. If [playingId]
 *    matches, calling [play] again toggles to stop.
 *  - [stop] releases playback and clears [playingId].
 *  - The underlying [MediaPlayer] is released when the composable
 *    leaves composition.
 *
 * Returned as a small struct so callers can destructure cleanly:
 * ```
 * val (playingId, play, stop) = rememberSingleMediaPlayer()
 * ```
 */
class SingleMediaPlayerController internal constructor(
    private val playingIdState: androidx.compose.runtime.MutableState<String?>,
    private val playerRef: MutableHolder,
) {
    val playingId: State<String?> get() = playingIdState

    fun play(id: String, path: String) {
        // Tapping the playing row toggles to stop.
        if (playingIdState.value == id) {
            stop()
            return
        }
        stop()
        runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    playingIdState.value = null
                    runCatching { release() }
                    playerRef.value = null
                }
                prepare()
                start()
                playerRef.value = this
                playingIdState.value = id
            }
        }.onFailure { e ->
            Log.w(TAG, "play failed for $path", e)
            playerRef.value?.runCatching { release() }
            playerRef.value = null
            playingIdState.value = null
        }
    }

    fun stop() {
        playerRef.value?.runCatching { release() }
        playerRef.value = null
        playingIdState.value = null
    }

    /** Mutable holder so the lambda doesn't capture a stale field. */
    internal class MutableHolder { var value: MediaPlayer? = null }
}

@Composable
fun rememberSingleMediaPlayer(): SingleMediaPlayerController {
    val idState = remember { mutableStateOf<String?>(null) }
    val holder = remember { SingleMediaPlayerController.MutableHolder() }
    val controller = remember { SingleMediaPlayerController(idState, holder) }
    DisposableEffect(controller) {
        onDispose { controller.stop() }
    }
    return controller
}