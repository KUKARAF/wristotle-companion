package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Call] — single
 * `contact` slot containing whatever name the user said.
 *
 * Strategy: strip the canonical call verbs (call/dial/phone/ring), drop
 * the filler set (my/the/please/up/etc.), strip trailing "a call" / "a
 * ring" / "on the phone", trim. Whatever remains is the contact name.
 *
 * Falls back to `emptyMap()` when stripping leaves nothing — the handler
 * will report "No contact specified" rather than dial a blank number.
 */
class CallSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        var working = query.lowercase().trim()

        // Strip call verbs anywhere they appear (handles "give mom a call",
        // "could you ring lisa", "i want to phone dad").
        working = CALL_VERBS.replace(working, " ")

        // Common filler words; replace with a single space so word boundaries
        // stay intact (otherwise "my mom" → "ymom").
        working = FILLERS.replace(working, " ")

        // Trailing "a call" / "a ring" / "on the phone".
        working = TRAILING.replace(working, "")

        // Collapse runs of whitespace, trim, drop trailing punctuation.
        working = working.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', '!', '?')

        // Drop trailing emphatic runs ("john yes yes yes" → "john"). Done
        // last so the earlier filler/trailing regexes don't have to know
        // about every yes/no/please variant.
        working = stripTrailingEmphasis(working)

        return if (working.isEmpty()) emptyMap() else mapOf("contact" to working)
    }

    private companion object {
        val CALL_VERBS = Regex("(?i)\\b(call|dial|phone|ring)\\b")
        // "give" and "get" pick up phrasings like "give <name> a call" /
        // "get me <name>"; "a" handles the article in "<verb> X a call" /
        // "want a call"; the rest are common conversational filler. Apply
        // word-boundary-anchored so we don't chew into contact-name characters.
        val FILLERS = Regex("(?i)\\b(give|get|my|a|the|please|would you|could you|can you|i want to|i'd like to|let's|to|up)\\b")
        // Trailing fragments that come after the contact name: "give X a call",
        // "call X on the phone", "call X for me", "call X back".
        val TRAILING = Regex("(?i)\\s*(a (call|ring)|on (the )?phone|for me|back)\\s*$")
    }
}
