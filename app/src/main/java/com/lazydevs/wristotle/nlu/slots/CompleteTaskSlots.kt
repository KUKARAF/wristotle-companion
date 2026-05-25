package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.CompleteTask] AND
 * [com.lazydevs.wristotle.speech.nlu.Intent.DeleteTask] — same shape,
 * different handlers.
 *
 * Returns:
 *   - `target` (String) — the text the user named, with the verb
 *     prefix + filler ("the", "task", "from my tasks") stripped. The
 *     handler substring-matches this against pending tasks. If `target`
 *     is one of the "last task" shortcut keywords, the handler bypasses
 *     text matching and picks the most-recently-created pending task.
 *
 * The shortcut keywords are intentionally narrow — "last", "latest",
 * "last task", "latest task", "most recent", "most recent task",
 * "most recently added", "the last one", "the latest one". Anything
 * else falls through to substring search.
 *
 * Used by both Complete and Delete because the parsing rules are
 * identical — only the handler verb differs.
 */
class CompleteTaskSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val target = query
            .replace(STRIP_PREFIXES, "")
            .replace(STRIP_TRAILING, "")
            .trim()
            .trim('.', ',', ':', ';', '!', '?', '-')
            .trim()
        return if (target.isBlank()) emptyMap() else mapOf("target" to target)
    }

    private companion object {
        /**
         * Strip everything up to the body — verb + optional filler
         * ("the", "task", "off"). Longest matches first so multi-word
         * forms ("mark off the task") win over single-word ones.
         */
        val STRIP_PREFIXES = Regex(
            """(?ix)
            ^\s*
            (
              # Complete-style verbs.
              (
                  (mark|check|tick|cross|knock|scratch)\s+off
                | (mark|check)\s+as\s+(done|complete|completed|finished)
                | (mark|check)\b
                | (complete|finish|finished|done|completed)\b
                | done\s+with
              )
              # Delete-style verbs (handled by same extractor; the
              # handler distinguishes via the routed intent).
              | (delete|remove|drop|forget|cancel|clear)\b
            )
            # Optional filler — "the task", "task", "my task", "off the task".
            (\s+(off|out))?
            (\s+(the|my))?
            (\s+tasks?)?
            (\s+(off|out))?
            (\s+(the|my))?
            (\s*[:,.;!?\-])?
            \s+
            """,
        )

        /**
         * Strip the trailing "from (my|the) tasks" / "from my todo"
         * tail when present. The user's body sits before this tail.
         */
        val STRIP_TRAILING = Regex(
            """(?ix)
            \s+
            (from|off|out\s+of)\s+
            (my|the)\s+
            (tasks?|task\s+list|to[- ]?do(\s+list)?|todos?)
            \s*$
            """,
        )
    }
}
