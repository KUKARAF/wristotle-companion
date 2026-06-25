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

        val matches = resolve(codes, subject)
        return when {
            matches.isEmpty() -> "Couldn't find a saved code for \"$subject\"."
            matches.size == 1 -> {
                transport.sendInt32(MessageKeys.SHOW_CODE_INDEX, matches[0])
                "Showing ${codes[matches[0]].label} on your watch."
            }
            // Ambiguous (e.g. several QR codes for "show my qr code") — don't
            // guess; name the candidates so the user can recall the right one.
            else -> "Which one? " + matches.joinToString(", ") { codes[it].label } +
                ". Say its name."
        }
    }

    /**
     * Candidate indices in the synced order, by the strongest tier that hits:
     * alias exact → alias substring → label substring → format hint. Returns ALL
     * matches in that tier so the handler can disambiguate when there's more than
     * one (the format fallback lets "show my qr code" work without a name).
     */
    private fun resolve(codes: List<SavedCode>, subject: String): List<Int> {
        val q = subject.lowercase()
        codes.indices.filter { codes[it].alias.equals(subject, ignoreCase = true) }
            .let { if (it.isNotEmpty()) return it }
        codes.indices.filter { codes[it].alias.isNotBlank() && codes[it].alias.lowercase().contains(q) }
            .let { if (it.isNotEmpty()) return it }
        codes.indices.filter { codes[it].label.lowercase().contains(q) }
            .let { if (it.isNotEmpty()) return it }
        if (q.contains("qr")) {
            codes.indices.filter { codes[it].format == CodeFormat.QR_CODE }
                .let { if (it.isNotEmpty()) return it }
        }
        if (q.contains("barcode") || q.contains("bar code") || q.contains("128")) {
            codes.indices.filter { codes[it].format.is1D }
                .let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }
}
