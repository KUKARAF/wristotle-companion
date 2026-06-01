package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.Note]:
 *   - `body` — the note text with the lead-in verb stripped.
 *
 * Lead-ins covered: "note", "note that", "note to self", "make a note (that/to)",
 * "take a note (that)", "save/store/keep/add (a)? note (that/about)",
 * "jot (this/that/it) down", "write (this/that/it)? down", "remember (that/to)",
 * "remind myself (that/to)", "noted (that)", "for my notes", "add to my notes".
 * If stripping leaves an empty body the handler falls back to the raw query.
 */
class NoteSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = query.replace(STRIP_PREFIXES, "").trim()
            .replaceFirstChar { it.uppercaseChar() }
        return if (body.isBlank()) emptyMap() else mapOf(SlotKeys.Body to body)
    }

    private companion object {
        // Anchored at start (^) so we only strip a true lead-in, not a "note"
        // mentioned mid-sentence. Trailing connector words (that / to / about /
        // colon) are absorbed so the body starts with the substance.
        val STRIP_PREFIXES = Regex(
            """(?ix)
            ^\s*
            (
              note\s+to\s+self\b
              | noted\b
              | (make|take|save|store|keep|add)\s+(a\s+)?notes?\b
              | add\s+to\s+my\s+notes?\b
              | for\s+my\s+notes?\b
              | jot\s+(this|that|it|down)\b
              | write\s+(this|that|it|down|it\s+down|that\s+down|this\s+down)\b
              | write\s+down\b
              | remember\b
              | notes?\b
            )
            (\s*[:,.;!?\-])?
            \s+
            (
              that\s+ | to\s+ | about\s+ | down\s+ | the\s+
            )?
            """,
        )
    }
}
