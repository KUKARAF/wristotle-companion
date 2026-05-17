package com.lazydevs.wristotle.speech.whisper

/**
 * Collapses immediately-repeating word sequences in Whisper transcripts.
 *
 * Whisper with greedy sampling + `single_segment=true` (our config) tends
 * to hallucinate phrase loops on short or trailing-silence audio:
 *
 *   "call me" → "call me. call me."
 *   "give john dial" → "give john dial give john dial give john dial dio"
 *   "(silence)" → "dio dio dio"
 *
 * This walks the token stream and, at each position, looks for the longest
 * n-gram that immediately repeats. When it finds one (`words[i..i+k] ==
 * words[i+k..i+2k]`), it emits the phrase once and skips every subsequent
 * consecutive copy.
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
        // Try the longest plausible repetition first; bounded at half the
        // remaining length since a repeat needs at least one full copy after.
        val maxK = (raw.size - i) / 2
        var k = maxK
        while (k >= 1) {
            if (normalized.subList(i, i + k) == normalized.subList(i + k, i + 2 * k)) {
                // Phrase of length k repeats. Keep one copy, advance past
                // every subsequent consecutive copy.
                for (j in 0 until k) keptIndices.add(i + j)
                var pos = i + k
                while (pos + k <= raw.size &&
                       normalized.subList(pos, pos + k) == normalized.subList(i, i + k)) {
                    pos += k
                }
                i = pos
                collapsed = true
                break
            }
            k--
        }
        if (!collapsed) {
            keptIndices.add(i)
            i++
        }
    }

    if (keptIndices.size == raw.size) return text   // nothing collapsed
    return keptIndices.joinToString(" ") { raw[it] }
}
