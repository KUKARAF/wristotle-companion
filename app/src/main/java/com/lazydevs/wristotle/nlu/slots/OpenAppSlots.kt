package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.OpenApp] — single
 * `app` slot containing the spoken app name. [com.lazydevs.wristotle.apps.AppIndex]
 * resolves the spoken form to an installed package id at dispatch.
 *
 * Strategy is symmetric to [CallSlots]: strip the leading verb
 * ("open"/"launch"/"start"/"fire up"/etc.) and a small filler set,
 * trim. Whatever remains is the spoken app name.
 *
 * Returns `emptyMap()` when stripping leaves nothing — the handler
 * reports an error rather than launching a blank.
 */
class OpenAppSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        var working = query.lowercase().trim()
        working = OPEN_VERBS.replace(working, " ")
        working = FILLERS.replace(working, " ")
        working = working.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', '!', '?')
        return if (working.isEmpty()) emptyMap() else mapOf("app" to working)
    }

    private companion object {
        // Multi-word verbs first so the matcher consumes "fire up" as a
        // unit rather than leaving "up" behind for the filler pass.
        val OPEN_VERBS = Regex(
            "(?i)\\b(fire up|bring up|switch to|go to|open|launch|start|run|load|show)\\b"
        )
        val FILLERS = Regex("(?i)\\b(the|my|please|app|application)\\b")
    }
}
