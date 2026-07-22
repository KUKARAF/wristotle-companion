// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.handlers

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.homeassistant.HaResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.settings.HomeAssistantSettings
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.stringSlot

/**
 * Forwards a spoken command to the user's self-hosted Home Assistant and
 * returns HA's spoken reply. Mirrors [AskAgentHandler] minus the LLM loop —
 * HA does its own NLU server-side, so there's a single POST and no tool
 * rounds. Read-only from Wristotle's side (HA is the authority on what the
 * command does); not in the confirm gate.
 *
 * Reads `settings.client()` on every call so a Settings edit (URL/token/
 * timeout) takes effect on the next voice command with no app restart. No
 * watch heartbeat like AskAgent's: HA's conversation pipeline is local and
 * fast, and the client's read timeout is kept under the watch's 15 s ceiling.
 */
class HomeAssistantHandler(
    private val settings: HomeAssistantSettings,
) : ActionHandler {

    override val tag: String = "home-assistant"
    override val intent: Intent = Intent.HomeAssistant
    // Reuse AskAgent's Q&A card kind so the watch renders the reply without a
    // new card type (no watch-firmware change needed).
    override val cardKind: String? = "agent_answer"

    override suspend fun handle(result: IntentResult): String {
        val command = result.stringSlot(SlotKeys.Query)
        if (command.isEmpty()) return NO_COMMAND_HINT
        return render(settings.client().process(command))
    }

    private fun render(r: HaResult): String = when (r) {
        is HaResult.Success -> r.speech
        HaResult.NotConfigured -> NOT_CONFIGURED_HINT
        is HaResult.Failure.BadAuth -> "Home Assistant: token rejected.\n${r.message}"
        is HaResult.Failure.Network -> "Home Assistant: can't reach it.\n${r.message}"
        is HaResult.Failure.Other -> "Home Assistant: ${r.message}"
    }

    private companion object {
        const val NO_COMMAND_HINT =
            "Say what?\nTry \"hey home assistant turn off the lights\"."
        const val NOT_CONFIGURED_HINT =
            "Home Assistant not set up.\nSettings → 🏠 Home Assistant → add your URL + token."
    }
}
