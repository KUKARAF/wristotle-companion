package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.MediaPlay] —
 * optional `app` slot when the user named a specific app to play in.
 *
 *   "play youtube"        → {app=youtube}
 *   "play audible"        → {app=audible}
 *   "play the song"       → {}                  (handler uses active session)
 *   "play music"          → {}                  (handler uses active session)
 *   "resume"              → {}                  (handler uses active session)
 *
 * The actual app→package resolution happens in the handler via
 * `AppIndex` — this extractor just isolates the candidate name. We
 * leave the generic-noun filtering to `AppIndex.find` so the rules
 * live in one place.
 *
 * Mirrors [OpenAppSlots] in shape but with the play-verb set rather
 * than the open-verb set; keeping them separate keeps the regex
 * scopes obvious at read time.
 */
class MediaPlaySlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        var working = query.lowercase().trim()
        working = PLAY_VERBS.replace(working, " ")
        working = FILLERS.replace(working, " ")
        working = working.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', '!', '?')
        return if (working.isEmpty()) emptyMap() else mapOf("app" to working)
    }

    private companion object {
        val PLAY_VERBS = Regex("(?i)\\b(play|resume|continue|start playing|start)\\b")
        // `on` covers "play music on spotify" → leaves "spotify".
        val FILLERS = Regex("(?i)\\b(the|some|my|please|app|application|on|in)\\b")
    }
}
