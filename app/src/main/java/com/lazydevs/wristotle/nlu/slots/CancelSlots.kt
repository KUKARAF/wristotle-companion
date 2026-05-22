package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Cancel].
 *
 * Extracts an optional `target` — the descriptor of *which* reminder to cancel
 * ("the gym one" → "gym", "my 5pm reminder" → "5pm"). Bare cancels ("cancel
 * that", "cancel my last reminder", "cancel it") strip down to nothing and emit
 * no target, which the handler reads as "cancel the most recent". A named
 * target that matches nothing is reported as not-found rather than falling back
 * to the latest (see CancelReminderHandler).
 */
class CancelSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val target = stripVerbBody(query, CANCEL_VERBS, FILLERS)
        return if (target.isBlank()) emptyMap() else mapOf("target" to target)
    }

    private companion object {
        val CANCEL_VERBS = Regex(
            "(?i)\\b(cancel|remove|delete|scratch|forget|kill|drop|undo|scrap|clear|never\\s*mind)\\b",
        )
        // Words that are never part of the reminder descriptor. "last/latest/
        // recent/it/that" reduce a bare cancel to empty → cancel the latest.
        val FILLERS = Regex(
            "(?i)\\b(the|my|that|this|a|an|reminder|reminders|alarm|alarms|about|for|to|set|just|one|please|last|latest|recent|it|i)\\b",
        )
    }
}
