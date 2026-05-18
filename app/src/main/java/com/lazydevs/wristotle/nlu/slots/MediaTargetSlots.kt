package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Shared `app` slot extractor for `Intent.MediaPause`, `MediaNext`, and
 * `MediaPrevious` — the same shape as [MediaPlaySlots] but with the
 * verb set covering those three intents.
 *
 *   "pause absorb"     → {app=absorb}
 *   "pause"            → {}
 *   "next youtube"     → {app=youtube}
 *   "previous spotify" → {app=spotify}
 *
 * The handler resolves `app` through [com.lazydevs.wristotle.apps.AppIndex]
 * and targets that specific session; when the slot is absent, it falls
 * back to today's behaviour (act on whichever session is currently
 * active). Generic noun filtering ("pause the song" / "next track")
 * lives inside `AppIndex.find` so it works the same for every intent.
 */
class MediaTargetSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        var working = query.lowercase().trim()
        working = VERBS.replace(working, " ")
        working = FILLERS.replace(working, " ")
        working = working.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', '!', '?')
        return if (working.isEmpty()) emptyMap() else mapOf("app" to working)
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
