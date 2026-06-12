// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.recognizer

/**
 * Whisper hallucination cleanup shared by every [Recognizer] backend.
 *
 * These were originally private to the local `WhisperRecognizer` (`:speech-whisper`),
 * so the cloud `HttpRecognizer` (`:speech`) returned raw transcripts with no
 * cleanup — repeated-phrase loops ("It's my name, it's my name, I think.") and
 * subtitle annotations ("*DING*") leaked straight into the NLU layer. Lifted here
 * to `:speech` (public) so both the local and cloud paths apply the same filters.
 */

/**
 * Collapses immediately-repeating word sequences in a transcript.
 *
 * Whisper with greedy sampling + `single_segment=true` tends to hallucinate
 * phrase loops on short or trailing-silence audio. The loop may also end with a
 * truncated *partial* copy of the unit, and "silence" itself can hallucinate as
 * a single token repeated several times.
 *
 * This walks the token stream and, at each position, looks for the **shortest**
 * n-gram that immediately repeats. When it finds one
 * (`words[i..i+k] == words[i+k..i+2k]`), it emits the phrase once, skips every
 * subsequent consecutive full copy, and additionally drops a trailing *partial*
 * copy whose tokens prefix-match the unit (handles Whisper truncating its last
 * hallucinated repeat).
 *
 * Why shortest-first: with longest-first, four copies of a 4-token unit could
 * match as `k=8` (two copies of an 8-token unit) and collapse the four down to
 * two instead of one. Shortest-first finds the natural sentence/phrase unit and
 * reduces all copies to one.
 *
 * Minimum unit length is **2 tokens**, AND the unit must contain more than one
 * distinct token. Both rules protect the same intent: don't shred a user's
 * emphatic repetition ("yes yes yes yes").
 *
 * Comparison is case-insensitive and strips trailing punctuation so `"call me"`
 * matches `"call me."`. Returns the original text unchanged when nothing
 * collapses, so the happy path is zero-cost.
 */
fun dedupeRepeatedPhrases(text: String): String {
    val raw = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (raw.size < 4) return text  // too short for meaningful repetition

    // Pre-normalize for matching only — we still emit the original tokens
    // so capitalization / punctuation in the surviving copy is preserved.
    val normalized = raw.map { it.lowercase().trimEnd('.', ',', '!', '?', ';', ':') }

    val keptIndices = ArrayList<Int>(raw.size)
    var i = 0
    while (i < raw.size) {
        var collapsed = false
        // Shortest-first: the smallest repeating unit is the natural phrase
        // boundary. Bounded at half the remaining length since a repeat
        // needs at least one full copy after. Minimum 2 — see docstring
        // for why single-word repeats are preserved as user content.
        val maxK = (raw.size - i) / 2
        var k = 2
        while (k <= maxK) {
            if (normalized.subList(i, i + k) == normalized.subList(i + k, i + 2 * k)) {
                // Skip unit that's effectively a single word repeated —
                // e.g. ["yes","yes"] is just two yeses, not a multi-word
                // hallucination pattern. Preserves emphatic user input.
                if (normalized.subList(i, i + k).toSet().size <= 1) {
                    k++
                    continue
                }
                // Phrase of length k repeats. Keep one copy, advance past
                // every subsequent consecutive copy.
                for (j in 0 until k) keptIndices.add(i + j)
                var pos = i + k
                while (pos + k <= raw.size &&
                       normalized.subList(pos, pos + k) == normalized.subList(i, i + k)) {
                    pos += k
                }
                // Also swallow a trailing *partial* copy when the leftover
                // tokens are a prefix of the unit. Without this, a Whisper
                // run that ends with a truncated last sentence still leaks
                // that fragment into the deduped text.
                val remaining = raw.size - pos
                if (remaining in 1 until k &&
                    normalized.subList(pos, pos + remaining) ==
                    normalized.subList(i, i + remaining)) {
                    pos += remaining
                }
                i = pos
                collapsed = true
                break
            }
            k++
        }
        if (!collapsed) {
            keptIndices.add(i)
            i++
        }
    }

    if (keptIndices.size == raw.size) return text   // nothing collapsed
    return keptIndices.joinToString(" ") { raw[it] }
}

/**
 * Returns "" when [text] is **entirely** a Whisper subtitle-annotation
 * hallucination (`*Door opens*`, `[laughter]`, `(silence)`, or several such
 * blocks chained), otherwise returns [text] unchanged.
 *
 * Whisper's training set includes subtitle data where sound effects are written
 * as inline annotations wrapped in `*…*`, `[…]`, or `(…)`. On silence it tends
 * to emit one of those as the whole transcript — `*sigh*`, `*DING*`, etc.
 * Dropping them lets the recognizer emit `ERROR_NO_MATCH` instead of routing
 * "Unknown command: *DING*" to the chat.
 *
 * Conservative on mixed content: if any non-annotation text remains after
 * stripping the blocks (e.g. `*sigh* call mom`), returns the ORIGINAL unchanged
 * — better to leak one stray annotation than to corrupt a real query.
 */
fun stripAnnotationOnly(text: String): String {
    val withoutAnnotations = text
        .replace(ANNOTATION_BLOCK, "")
        .trim()
        .trim('.', ',', '!', '?', ';', ':', '-', '—')
        .trim()
    return if (withoutAnnotations.isEmpty()) "" else text
}

// Three bracket flavours Whisper uses for inline sound annotations.
private val ANNOTATION_BLOCK = Regex("""\*[^*]+\*|\[[^\]]+\]|\([^)]+\)""")
