package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.AskAgent]:
 *   - `query` — the question text, with the "ask agent" / "ask claude" /
 *     "ask the agent" lead-in stripped.
 *
 * Lead-ins covered:
 *   - "ask (the )?agent ..."
 *   - "ask claude ..."  (handy alias since the LLM may well be Claude)
 *   - "ask (the )?(ai|llm|assistant) ..."
 *   - "hey agent ..."   (less common but matches the wakeword shape some
 *                        users default to)
 *
 * Trailing punctuation right after the lead-in ("ask agent:", "ask
 * agent,") is consumed so the body doesn't start with a stray comma.
 */
class AskAgentSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = query.replace(STRIP_PREFIXES, "").trim()
        return if (body.isBlank()) emptyMap() else mapOf("query" to body)
    }

    private companion object {
        val STRIP_PREFIXES = Regex(
            """(?ix)
            ^\s*
            (
              (ask|hey)\s+(the\s+)?(agent|claude|ai|llm|assistant|bot|chatbot|chat\s*gpt|gpt)\b
            )
            (\s*[:,.;!?\-])?
            \s+
            """,
        )
    }
}
