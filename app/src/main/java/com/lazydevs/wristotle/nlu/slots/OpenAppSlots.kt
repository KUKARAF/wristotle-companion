package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.OpenApp] — single
 * `app` slot containing the spoken app name. The handler resolves the
 * spoken form to an installed package via `AppIndex`.
 */
class OpenAppSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = stripVerbBody(query, VERBS, FILLERS)
        return if (body.isEmpty()) emptyMap() else mapOf("app" to body)
    }

    private companion object {
        // Multi-word verbs listed first so the matcher consumes "fire up"
        // / "switch to" as a unit before the single-word pass picks at
        // "up" / "to" individually.
        val VERBS = Regex(
            "(?i)\\b(fire up|bring up|switch to|go to|open|launch|start|run|load|show)\\b"
        )
        val FILLERS = Regex("(?i)\\b(the|my|please|app|application)\\b")
    }
}
