// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.AddTask]:
 *   - `body` — the task text, with the spoken lead-in stripped.
 *
 * Lead-ins covered:
 *   - `"add task X"`
 *   - `"new task X"`
 *   - `"create (a)? task X"`
 *   - `"task X"` (bare — short, unambiguous opener)
 *   - `"add X to (my|the) tasks?"` / `"add X to (my)? (to-do|todo|to do) list"`
 *   - `"put X on (my)? tasks?"` / `"put X on (my)? to-do"`
 *
 * **Anti-rule (handled in PrefixHints, not here):** *"remind me to X"*
 * and *"remember to X"* stay Reminder / Note respectively, even though
 * both shapes contain "to X" which could superficially look like a task.
 * The ordering in PrefixHints + the deliberately-narrow trigger words
 * here keep the boundary clean.
 *
 * Body returned with the first character upper-cased so the task reads
 * as a sentence in the Tasks tab.
 */
class AddTaskSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val body = query.replace(STRIP_PREFIXES, "")
            // Also strip a trailing "to my tasks" / "to my todo" if the
            // shape was "add X to my tasks" — the leading "add" was
            // already consumed by STRIP_PREFIXES, but the connector tail
            // remains.
            .replace(STRIP_TRAILING_CONNECTOR, "")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }
        return if (body.isBlank()) emptyMap() else mapOf(SlotKeys.Body to body)
    }

    private companion object {
        val STRIP_PREFIXES = Regex(
            """(?ix)
            ^\s*
            (
              # "add/create/make/new task[s] ..." — Whisper routinely
              # pluralises ("add tasks" instead of "add task"), so the
              # regex accepts either form. The user said "task" once
              # and Whisper transcribed it as "tasks" in real testing.
              (add|create|make|new)\s+(a\s+|an\s+)?tasks?\b
              # "add to my tasks X" / "put on my todo X" — leading-noun
              # shape where the tasks-noun appears BEFORE the body.
              # Whisper transcribed "add task to buy oyster" as "add to
              # my tasks by oyster" in real testing, so the slot
              # extractor needs to peel "add to my tasks" off the front.
              # Must sit ABOVE the bare "add" / "put" branch below or
              # the latter consumes only the verb and leaves "to my
              # tasks X" in the body.
              | (add|put)\s+(to|on|in|onto)\s+(my|the)\s+(tasks?|to[- ]?do(\s+list)?|todos?)\b
              # "add ... to (my|the) tasks/to-do list" — strip just the
              # leading verb here; the trailing "to my tasks" is consumed
              # by STRIP_TRAILING_CONNECTOR below.
              | (add|put)\b
              # Bare "task[s] X" — short, unambiguous opener. Sits LAST
              # so the longer "add task X" matches first when both could.
              | tasks?\b
            )
            (\s*[:,.;!?\-])?
            \s+
            # Optional connector after the verb — "add task TO buy
            # milk", "task THAT I need to call mom", "add task ABOUT
            # the meeting". Whisper produces these phrasings about half
            # the time when the user pauses mid-utterance.
            (
              to\s+ | that\s+ | about\s+
            )?
            """,
        )

        /**
         * "add X to my tasks" / "put X on my todo list" — strip the
         * trailing connector + tasks-noun so the body is just X.
         * Run AFTER STRIP_PREFIXES has eaten the leading verb.
         */
        val STRIP_TRAILING_CONNECTOR = Regex(
            """(?ix)
            \s+
            (to|on|in|onto)\s+
            (my|the)\s+
            (task|tasks|to[- ]?do(\s+list)?|todos?)
            \s*$
            """,
        )
    }
}