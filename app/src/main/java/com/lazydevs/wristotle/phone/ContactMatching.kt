// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.phone

/**
 * Similarity scoring for contact-name lookups, factored out of
 * [ContactsRepository] so it's pure and unit-testable.
 *
 * Why this exists: the old lookup took the first `LIKE %query%` row (prefix
 * match preferred) with no similarity floor, so a garbled transcription could
 * snap to a contact that merely *contains* the query as a mid-word substring —
 * e.g. "al" matching "Michael" — and the wrong person got texted/called. The
 * scorer lets the repository reject a best-but-poor match and report "not
 * found" instead of silently acting on the wrong contact.
 */

/** Below this score a candidate is treated as "no match". Prefix / whole-token
 *  matches score well above it; mere mid-word substring overlaps fall below. */
internal const val CONTACT_MATCH_FLOOR = 0.6f

/**
 * Scores how well [query] matches a contact's [name], 0f..1f. Higher is better.
 * Case-insensitive, whitespace-trimmed. Rewards exact / prefix / whole-token
 * matches; mid-word substring overlaps fall back to edit-distance similarity,
 * which is low for dissimilar names.
 */
internal fun contactMatchScore(query: String, name: String): Float {
    val q = query.trim().lowercase()
    val n = name.trim().lowercase()
    if (q.isEmpty() || n.isEmpty()) return 0f
    if (q == n) return 1.0f

    val tokens = n.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (n.startsWith(q)) return 0.95f
    if (tokens.any { it == q }) return 0.92f
    if (tokens.any { it.startsWith(q) }) return 0.85f

    // No prefix/token match — fall back to the best edit-distance similarity
    // against any single token or the whole name. Dissimilar names score low.
    return (tokens + n).maxOf { editSimilarity(q, it) }
}

/** 1 - normalized Levenshtein distance, 0f..1f. */
private fun editSimilarity(a: String, b: String): Float {
    val maxLen = maxOf(a.length, b.length)
    if (maxLen == 0) return 1f
    return 1f - levenshtein(a, b).toFloat() / maxLen
}

/** Standard Levenshtein edit distance (two-row DP — names are short). */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                prev[j] + 1,        // deletion
                curr[j - 1] + 1,    // insertion
                prev[j - 1] + cost, // substitution
            )
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}