// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Shared `app` slot extractor for `Intent.MediaPause`, `MediaNext`, and
 * `MediaPrevious` — same shape as [MediaPlaySlots] with the verb set
 * widened to cover those three intents.
 *
 *   "pause absorb"     → {app=absorb}
 *   "next youtube"     → {}                  (filler "track" / "song" strips body)
 *   "pause"            → {}                  (handler uses active session)
 */
class MediaTargetSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = stripVerbBody(query, VERBS, FILLERS)
        return if (body.isEmpty()) emptyMap() else mapOf(SlotKeys.App to body)
    }

    private companion object {
        val VERBS = Regex(
            "(?i)\\b(pause|halt|stop|next|skip|previous|last|go back)\\b"
        )
        val FILLERS = Regex(
            "(?i)\\b(the|my|some|please|on|in|app|application|song|track|episode|this)\\b"
        )
    }
}