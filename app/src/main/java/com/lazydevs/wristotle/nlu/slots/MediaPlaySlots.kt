package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.MediaPlay] —
 * optional `app` slot when the user named a specific app to play in.
 *
 *   "play youtube"        → {app=youtube}
 *   "play the song"       → {}                  (handler uses active session)
 *   "resume"              → {}                  (handler uses active session)
 *
 * Generic-noun handling lives in `AppIndex.lookup` so extractors don't
 * have to know about "music" / "song" / "podcast" individually.
 */
class MediaPlaySlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = stripVerbBody(query, VERBS, FILLERS)
        return if (body.isEmpty()) emptyMap() else mapOf(SlotKeys.App to body)
    }

    private companion object {
        val VERBS = Regex("(?i)\\b(play|resume|continue|start playing|start)\\b")
        // `on` covers "play music on spotify" → leaves "spotify".
        val FILLERS = Regex("(?i)\\b(the|some|my|please|app|application|on|in)\\b")
    }
}
