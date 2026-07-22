// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor
// kotlin.concurrent.Volatile is the multiplatform @Volatile; the unqualified
// @Volatile resolves to kotlin.jvm.Volatile which doesn't exist on K/N.
import kotlin.concurrent.Volatile

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.HomeAssistant]:
 *   - `query` — the command text, with the "hey/ask/tell <subject>" lead-in
 *     stripped.
 *
 * Subject keywords come from [HomeAssistantTriggers.DEFAULT_SUBJECTS]
 * (`home assistant` / `homeassistant` / `hass`) plus whatever the user added
 * in Settings → Home Assistant → Custom trigger words (e.g. `"jarvis"`).
 *
 * @property extrasProvider Snapshot of the user's custom trigger words; called
 * on every `extract` so a Settings change takes effect on the next voice
 * query, but the compiled [Regex] is cached keyed by the extras snapshot.
 */
class HomeAssistantSlots(
    private val extrasProvider: () -> List<String> = { emptyList() },
) : SlotExtractor {

    @Volatile private var cachedExtras: List<String> = emptyList()
    @Volatile private var cachedRegex: Regex = HomeAssistantTriggers.stripRegex(emptyList())

    override suspend fun extract(query: String): Map<String, Any> {
        val regex = currentRegex()
        val body = query.replace(regex, "").trim()
        return if (body.isBlank()) emptyMap() else mapOf(SlotKeys.Query to body)
    }

    private fun currentRegex(): Regex {
        val extras = extrasProvider()
        if (extras == cachedExtras) return cachedRegex
        val rebuilt = HomeAssistantTriggers.stripRegex(extras)
        cachedExtras = extras
        cachedRegex = rebuilt
        return rebuilt
    }
}
