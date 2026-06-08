// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.AskAgent]:
 *   - `query` — the question text, with the "ask <subject>" / "hey
 *     <subject>" lead-in stripped.
 *
 * Subject keywords come from [AskAgentTriggers.DEFAULT_SUBJECTS]
 * (`agent` / `claude` / `ai` / `llm` / `assistant` / `bot` / `chatbot`
 * / `chatgpt` / `gpt`) plus whatever the user added in
 * Settings → Ask Agent → Custom trigger words (e.g. `"jarvis"`).
 *
 * Trailing punctuation right after the lead-in ("ask agent:", "ask
 * agent,") is consumed so the body doesn't start with a stray comma.
 *
 * @property extrasProvider Snapshot of the user's custom trigger words.
 * Called on every `extract` so a Settings change takes effect on the
 * next voice query — but we cache the compiled `Regex` keyed by the
 * extras list, so equal snapshots reuse the same compiled regex
 * (PrefixHints' anchored regex is the hot path; this one runs only
 * for the AskAgent intent).
 */
class AskAgentSlots(
    private val extrasProvider: () -> List<String> = { emptyList() },
) : SlotExtractor {

    @Volatile private var cachedExtras: List<String> = emptyList()
    @Volatile private var cachedRegex: Regex = AskAgentTriggers.stripRegex(emptyList())

    override suspend fun extract(query: String): Map<String, Any> {
        val regex = currentRegex()
        val body = query.replace(regex, "").trim()
        return if (body.isBlank()) emptyMap() else mapOf(SlotKeys.Query to body)
    }

    private fun currentRegex(): Regex {
        val extras = extrasProvider()
        if (extras == cachedExtras) return cachedRegex
        val rebuilt = AskAgentTriggers.stripRegex(extras)
        cachedExtras = extras
        cachedRegex = rebuilt
        return rebuilt
    }
}