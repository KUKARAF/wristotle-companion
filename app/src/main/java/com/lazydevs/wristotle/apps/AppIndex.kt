package com.lazydevs.wristotle.apps

import android.util.Log

private const val TAG = "AppIndex"

/**
 * Read-side facade over [InstalledAppDao]. Resolves a free-form
 * spoken phrase ("audible", "youtube music", "spotify") to a
 * specific launcher package id.
 *
 * Matching tiers, in order — first hit wins:
 *   1. exact normalized equality
 *   2. label prefix-starts-with query  (shortest label wins)
 *   3. label substring contains query  (shortest label wins)
 *
 * Generic media nouns are filtered out before any lookup — without
 * the denylist, "play music" / "play that song" would pull in any
 * app whose name happens to contain "music" or "song" (Apple Music,
 * Google Play Music, etc.) when the user really meant "resume on
 * the active session". The denylist makes [MediaPlayHandler]'s
 * fallback-to-active-session path keep working for those phrasings.
 */
class AppIndex(private val dao: InstalledAppDao) {

    suspend fun count(): Int = dao.count()
    suspend fun latestScanAt(): Long? = dao.latestScanAt()

    /**
     * Returns the package id that best matches [query], or null when
     * no row matches or the query reduces to a generic noun after
     * normalization.
     */
    suspend fun find(query: String): String? {
        val normalized = normalizeForIndex(query)
        if (normalized.isEmpty()) return null
        if (GENERIC_NOUNS.contains(normalized)) return null
        // Strip leading generic articles / determiners that show up in
        // dictation but never appear in app labels: "the youtube" →
        // "youtube", "my spotify" → "spotify".
        val trimmed = stripLeadingArticles(normalized)
        if (trimmed.isEmpty() || GENERIC_NOUNS.contains(trimmed)) return null

        dao.findExact(trimmed)?.let {
            Log.d(TAG, "exact match: '$query' → ${it.label} (${it.packageId})")
            return it.packageId
        }
        dao.findByPrefix(trimmed)?.let {
            Log.d(TAG, "prefix match: '$query' → ${it.label} (${it.packageId})")
            return it.packageId
        }
        // Only fall to substring match for queries ≥4 chars to avoid
        // tiny needles (e.g. "do") matching half the launcher.
        if (trimmed.length >= 4) {
            dao.findByContains(trimmed)?.let {
                Log.d(TAG, "contains match: '$query' → ${it.label} (${it.packageId})")
                return it.packageId
            }
        }
        Log.d(TAG, "no match for '$query' (normalized='$trimmed')")
        return null
    }

    private fun stripLeadingArticles(normalized: String): String {
        var s = normalized
        for (prefix in LEADING_ARTICLES) {
            if (s.startsWith("$prefix ")) s = s.removePrefix("$prefix ")
        }
        return s.trim()
    }

    private companion object {
        /**
         * Words that should NEVER trigger an app lookup — the user is
         * referring to generic media content, not an app named after
         * the content type. "play music" → fall through to active
         * session, not "Music" app.
         */
        val GENERIC_NOUNS: Set<String> = setOf(
            "music", "song", "songs", "track", "tracks", "playlist",
            "podcast", "podcasts", "audio", "video", "videos", "sound",
            "sounds", "playback", "media", "anything", "something",
            "it", "that", "this",
        )
        val LEADING_ARTICLES = listOf("the", "my", "some", "a", "an")
    }
}
