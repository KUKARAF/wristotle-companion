package com.lazydevs.wristotle.speech.whisper

/**
 * Collapses immediately-repeating word sequences in Whisper transcripts.
 *
 * Whisper with greedy sampling + `single_segment=true` (our config) tends
 * to hallucinate phrase loops on short or trailing-silence audio. The
 * loop may also end with a truncated *partial* copy of the unit, and
 * "silence" itself can hallucinate as a single token repeated several
 * times.
 *
 * This walks the token stream and, at each position, looks for the
 * **shortest** n-gram that immediately repeats. When it finds one
 * (`words[i..i+k] == words[i+k..i+2k]`), it emits the phrase once,
 * skips every subsequent consecutive full copy, and additionally drops
 * a trailing *partial* copy whose tokens prefix-match the unit
 * (handles Whisper truncating its last hallucinated repeat).
 *
 * Why shortest-first: with longest-first, four copies of a 4-token unit
 * could match as `k=8` (two copies of an 8-token unit) and collapse the
 * four down to two instead of one. Shortest-first finds the natural
 * sentence/phrase unit and reduces all copies to one.
 *
 * Minimum unit length is **2 tokens**, AND the unit must contain more
 * than one distinct token. Both rules protect the same intent: don't
 * shred a user's emphatic repetition. Without the "distinct" rule, four
 * copies of the same single word ("yes yes yes yes") would look like
 * two copies of the 2-token unit `[yes yes]` and collapse to half,
 * regardless of how many times the user actually said it.
 *
 * The one real single-word hallucination Whisper produces is on silence
 * (`dio dio dio`), which has no matching intent and routes to Unknown
 * either way, so leaving any all-same-word run intact costs nothing
 * meaningful.
 *
 * Comparison is case-insensitive and strips trailing punctuation so
 * `"call me"` matches `"call me."` (Whisper's punctuation placement on
 * repetitions is inconsistent).
 *
 * Returns the original text unchanged when nothing collapses, so the
 * happy path is zero-cost in practice.
 */
internal fun dedupeRepeatedPhrases(text: String): String {
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
 * hallucination (`*Door opens*`, `[laughter]`, `(silence)`, or several
 * such blocks chained), otherwise returns [text] unchanged.
 *
 * Whisper's training set includes large amounts of subtitle data where
 * sound effects are written as inline annotations wrapped in `*…*`,
 * `[…]`, or `(…)`. When given silence or near-silent audio it tends to
 * emit one of those annotations as the whole transcript — `*sigh*` for
 * a quick mic blip, `*DING*` for a noisy environment, etc. Forwarding
 * those to the NLU layer produces "Unknown command" noise in the chat;
 * dropping them lets the recognizer emit `ERROR_NO_MATCH` instead and
 * the watch shows nothing.
 *
 * Conservative on mixed content: if the transcript has any non-
 * annotation text after stripping the blocks (e.g. `*sigh* call mom`),
 * we return the ORIGINAL unchanged — better to leak one stray
 * annotation than to silently corrupt a real query.
 */
internal fun stripAnnotationOnly(text: String): String {
    val withoutAnnotations = text
        .replace(ANNOTATION_BLOCK, "")
        .trim()
        .trim('.', ',', '!', '?', ';', ':', '-', '—')
        .trim()
    return if (withoutAnnotations.isEmpty()) "" else text
}

// Three bracket flavours Whisper uses for inline sound annotations.
// `[^X]+` keeps the matches tight (no greedy run past the closing
// bracket) and the per-flavour alternatives let us catch chains like
// `*sigh* [cough] (silence)` in a single sweep.
private val ANNOTATION_BLOCK = Regex("""\*[^*]+\*|\[[^\]]+\]|\([^)]+\)""")
