package com.lazydevs.wristotle.apps

/**
 * Compound-word-tolerant alias lookup. STT engines (Whisper especially)
 * split or join compound words inconsistently between transcriptions —
 * an alias stored as "audiobook" should still resolve when the user
 * speaks it and the model transcribes "audio book", and vice versa.
 *
 * Resolution order:
 *   1. Exact match on the already-normalized [phrase].
 *   2. Match on the space-collapsed form of [phrase] (handles the
 *      "stored compound, spoken split" direction).
 *   3. Linear scan of [map], comparing each key's space-collapsed form
 *      against the space-collapsed [phrase] (handles the
 *      "stored split, spoken compound" direction).
 *
 * Direct matches always win — if the user stored both "audiobook" and
 * "audio book" pointing at *different* apps, the spoken form's exact
 * match takes precedence. The fallback only fires when the phrase
 * doesn't appear verbatim in the alias map.
 *
 * Alias maps are tiny (handfuls of entries on a realistic phone), so
 * the linear scan in step 3 is fine.
 */
internal fun resolveAliasWithCompoundFallback(
    map: Map<String, String>,
    phrase: String,
): String? {
    map[phrase]?.let { return it }
    val compound = phrase.replace(" ", "")
    if (compound != phrase) {
        map[compound]?.let { return it }
    }
    return map.entries.firstOrNull { (k, _) -> k.replace(" ", "") == compound }?.value
}
