// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.codes

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.handler.ActionHandler
import com.lazydevs.wristotle.speech.nlu.slots.SlotKeys
import com.lazydevs.wristotle.speech.nlu.stringSlot
import com.lazydevs.wristotle.speech.nlu.transport.MessageKeys
import com.lazydevs.wristotle.speech.nlu.transport.WatchTransport

/**
 * Voice recall — "show my tesco" renders that saved code on the watch.
 *
 * Resolves the spoken `subject` against the saved codes (alias first, then label
 * substring) and tells the watch to render the cached code at that index. The
 * candidate list is filtered to encodable codes **in the same order
 * [CodeSyncSender] pushes them**, so the index lines up with the watch's cache.
 * Inherently online (the phone does the NLU), so that cache is current.
 *
 * commonMain — every dependency ([CodeRepository], [WatchTransport],
 * [CodeGenerator]) is a shared seam, so iOS reuses it unchanged.
 */
class ShowCodeHandler(
    private val repository: CodeRepository,
    private val transport: WatchTransport,
) : ActionHandler {

    override val tag = "show_code"
    override val intent = Intent.ShowCode

    override suspend fun handle(result: IntentResult): String {
        val codes = repository.all().filter { CodeGenerator.matrix(it) != null }
        if (codes.isEmpty()) return "You haven't saved any codes yet."

        val subject = result.stringSlot(SlotKeys.Subject)
        if (subject.isBlank()) {
            return "Which code? You have: " + codes.joinToString(", ") { it.label } + "."
        }

        val index = resolve(codes, subject)
        if (index < 0) return "Couldn't find a saved code for \"$subject\"."

        transport.sendInt32(MessageKeys.SHOW_CODE_INDEX, index)
        return "Showing ${codes[index].label} on your watch."
    }

    /** Alias exact → alias substring → label substring. -1 if nothing matches. */
    private fun resolve(codes: List<SavedCode>, subject: String): Int {
        val q = subject.lowercase()
        codes.indexOfFirst { it.alias.equals(subject, ignoreCase = true) }.let { if (it >= 0) return it }
        codes.indexOfFirst { it.alias.isNotBlank() && it.alias.lowercase().contains(q) }.let { if (it >= 0) return it }
        codes.indexOfFirst { it.label.lowercase().contains(q) }.let { if (it >= 0) return it }
        return -1
    }
}
