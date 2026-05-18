package com.lazydevs.wristotle.apps

/**
 * Lookup-key normalization shared by [AppIndexer] (writer) and
 * [AppIndex] (reader). Lowercases, strips anything that isn't a letter
 * or digit (so "Audible: Audiobooks" / "Audible — Audiobooks" /
 * "Audible" all collapse to the same key "audibleaudiobooks" /
 * "audible"), then collapses runs of whitespace. Keeping both sides
 * funnelled through a single helper is what makes the SQL `LIKE`
 * matchers in [InstalledAppDao] reliable.
 */
internal fun normalizeForIndex(text: String): String =
    text.lowercase()
        .replace(NON_ALNUM_OR_SPACE, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

private val NON_ALNUM_OR_SPACE = Regex("[^a-z0-9 ]")
private val MULTI_SPACE = Regex("\\s+")
