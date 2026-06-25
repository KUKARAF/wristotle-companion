// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.ShowCode] — recalling a
 * saved QR/barcode to the watch by spoken name or alias.
 *
 * Extracts a single `subject` (String): the alias/label the user spoke, with the
 * lead-in verb ("show my", "pull up my", "open my", …) and a trailing noun
 * ("code", "barcode", "card", "pass") stripped. Resolution of that subject to an
 * actual code lives in the handler — this stays pure + provider-free.
 *
 *   "show my tesco"            → subject="tesco"
 *   "pull up my clubcard"      → subject="clubcard"
 *   "show the gym card"        → subject="gym"
 *   "show my codes"            → subject=null (bare → open the list)
 */
class ShowCodeSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val subject = extractSubject(query)
        return if (subject.isNullOrBlank()) emptyMap() else mapOf(SlotKeys.Subject to subject)
    }

    private fun extractSubject(query: String): String? {
        // Dictation usually adds a trailing period ("Show QR card.") — strip
        // surrounding punctuation BEFORE matching or the trailing-noun anchor fails.
        var s = LEAD_IN.replace(query.trim().trim('.', '!', '?', ','), "").trim()
        // "show my codes/barcodes" with nothing else is a request for the list,
        // not a specific code — leave the subject empty.
        if (s.equals("codes", ignoreCase = true) || s.equals("barcodes", ignoreCase = true)) return null
        // Drop a trailing "code/card/pass" — but only when something is left in
        // front of it, so a bare "barcode" / "qr code" survives as a format hint
        // for the handler ("show my barcode" → subject="barcode").
        val stripped = TRAILING_NOUN.replace(s, "").trim()
        if (stripped.isNotEmpty()) s = stripped
        return s.ifBlank { null }
    }

    private companion object {
        val LEAD_IN = Regex(
            "^(?:can you |could you |please )?(?:show|pull up|bring up|open|display|get|find|see)" +
                "(?: me)?(?: up)?(?: my| the| a| me my)?\\s+",
            RegexOption.IGNORE_CASE,
        )
        val TRAILING_NOUN = Regex("\\s+(?:qr ?code|bar ?code|code|card|pass)$", RegexOption.IGNORE_CASE)
    }
}
