// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractor

/** Which sports query the user asked for. */
enum class SportKind { NEXT, LAST, LIVE, STANDINGS }

/**
 * Slots for [com.lazydevs.wristotle.speech.nlu.Intent.SportScore].
 *
 * Two slots:
 *  - `sportKind` ([SportKind]) — inferred from the verb. Order matters:
 *    STANDINGS → LIVE → LAST → NEXT, so "live **score**" reads as LIVE (not
 *    LAST's "score") and bare team names default to NEXT.
 *  - `subject` (String, optional) — the spoken team/league with the question
 *    scaffolding stripped. Omitted when the residue is a pronoun ("did **we**
 *    win") so the handler falls back to a saved favorite.
 *
 *  "when do the warriors play next"          → NEXT,  "warriors"
 *  "did arsenal win"                         → LAST,  "arsenal"
 *  "what's the live score for the lakers"    → LIVE,  "lakers"
 *  "premier league table"                    → STANDINGS, "premier league"
 *  "did we win"                              → LAST,  (no subject → favorite)
 *  "warriors"                                → NEXT,  "warriors"
 *
 * Pure (no network, no provider knowledge) — resolution of the subject to a
 * real team happens later in the handler via the sports library.
 */
class SportSlots : SlotExtractor {

    override suspend fun extract(query: String): Map<String, Any> {
        val kind = inferKind(query)
        val subject = extractSubject(query)
        return buildMap {
            put(SlotKeys.SportKind, kind)
            if (subject != null) put(SlotKeys.Subject, subject)
        }
    }

    private fun inferKind(query: String): SportKind = when {
        STANDINGS.containsMatchIn(query) -> SportKind.STANDINGS
        LIVE.containsMatchIn(query) -> SportKind.LIVE
        LAST.containsMatchIn(query) -> SportKind.LAST
        NEXT.containsMatchIn(query) -> SportKind.NEXT
        else -> SportKind.NEXT
    }

    private fun extractSubject(query: String): String? {
        val residue = stripVerbBody(query, VERBS, FILLERS).trim()
        if (residue.isEmpty()) return null
        if (residue in PRONOUNS) return null // "did we win" → use the favorite
        return residue
    }

    private companion object {
        val STANDINGS = Regex("(?i)\\b(standings?|table|league position|where (are|do|is)|top of)\\b")
        val LIVE = Regex("(?i)\\b(live|right now|currently|in[- ]?game|what'?s the score)\\b")
        val LAST = Regex("(?i)\\b(last|won|win|wins|winning|lose|loses|lost|losing|beat|beats|score|scores|result|results|final|how did)\\b")
        val NEXT = Regex("(?i)\\b(next|upcoming|fixtures?|when (do|is|are|does)|who (do|are)|play(ing|s)?)\\b")

        // Stripped to leave the bare team/league. Deliberately omits "league"
        // (part of subjects like "premier league") and "live" handling beyond
        // the kind verbs.
        val VERBS = Regex(
            "(?i)\\b(what'?s|what|when|where|how|who|do|does|did|is|are|will|" +
                "score|scores|result|results|standings?|table|next|last|upcoming|" +
                "fixtures?|playing|plays|play|win|wins|won|winning|lose|loses|lost|" +
                "losing|beat|beats|live|currently|doing|going|tell)\\b",
        )
        val FILLERS = Regex(
            "(?i)\\b(the|a|an|for|of|on|in|to|me|vs|against|right now|now|" +
                "tonight|today|tomorrow|game|games|match|matches|position)\\b",
        )
        val PRONOUNS = setOf("we", "us", "they", "them", "our team", "my team", "our", "my")
    }
}

/** Reads the inferred kind (defaults to NEXT if absent/wrong type). */
fun Map<String, Any>.sportKind(): SportKind = this[SlotKeys.SportKind] as? SportKind ?: SportKind.NEXT

/** Reads the spoken subject, or null when the user meant a saved favorite. */
fun Map<String, Any>.sportSubject(): String? = this[SlotKeys.Subject] as? String
