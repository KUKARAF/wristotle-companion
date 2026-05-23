package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.AppendNote]:
 *   - `body` — the text to append, with the lead-in stripped.
 *
 * Lead-ins covered:
 *   - "add to (the|my)? (previous|last|latest|recent) note(s)"
 *   - "append to (the|my)? note(s)"
 *   - "append note(s)"
 *   - "append"
 * Each optionally followed by punctuation + a connector ("that", "the",
 * "about", ":").
 */
class AppendNoteSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = query.replace(STRIP_PREFIXES, "").trim()
            .replaceFirstChar { it.uppercaseChar() }
        return if (body.isBlank()) emptyMap() else mapOf("body" to body)
    }

    private companion object {
        val STRIP_PREFIXES = Regex(
            """(?ix)
            ^\s*
            (
              (add|append)\s+to\s+(the\s+|my\s+)?(previous|last|latest|recent)\s+notes?\b
              | append\s+(to\s+(the\s+|my\s+)?)?notes?\b
              | append\b
            )
            (\s*[:,.;!?\-])?
            \s+
            (
              that\s+ | the\s+ | about\s+
            )?
            """,
        )
    }
}
