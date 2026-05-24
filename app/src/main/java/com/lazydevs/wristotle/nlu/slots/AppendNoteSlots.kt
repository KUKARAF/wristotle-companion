package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.AppendNote]:
 *   - `body` — the text to append, with the lead-in stripped.
 *
 * Lead-ins covered:
 *   - "(add|append|amend) to (the|my)? (previous|last|latest|recent) note(s)"
 *   - "(append|amend) to (the|my)? note(s)"
 *   - "(append|amend) note(s)"
 *   - "append" or "amend"
 *
 * `amend` is included because Whisper consistently mishears `append` as
 * `amend`, and `amend` is semantically unambiguous (can only modify an
 * existing thing) so it routes the same way without ambiguity.
 *
 * Each lead-in is optionally followed by punctuation + a connector
 * ("that", "the", "about", ":").
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
              # "add/append/amend to (the|my) (previous|last|latest|recent) note(s)"
              (add|append|amend)\s+to\s+(the\s+|my\s+)?(previous|last|latest|recent)\s+notes?\b
              # "append/amend (the|my) (previous|last|latest|recent) note(s)" —
              # no "to". Catches "amend my last note", "append the previous notes".
              # Must sit ABOVE the bare-verb alternative below or the latter
              # consumes only the verb and leaves "my last note ..." in the body.
              | (append|amend)\s+(the\s+|my\s+)?(previous|last|latest|recent)\s+notes?\b
              # "append/amend (to (the|my))? note(s)" — bare "amend the notes",
              # "append my notes", etc.
              | (append|amend)\s+(to\s+(the\s+|my\s+)?)?notes?\b
              # Bare verb — "append body", "amend body".
              | (append|amend)\b
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
