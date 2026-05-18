package com.lazydevs.wristotle.apps

import android.util.Log

private const val TAG = "AppIndex"

/**
 * Three-way result distinguishing "user named an app we found",
 * "user said a generic media noun" ("play the song"), and "user
 * named something we don't recognise" ("play absolpt"). The
 * distinction matters because handlers should fall through to the
 * active session ONLY for generic phrasings — not for unrecognised
 * specific names, where silently acting on the wrong app would be
 * worse than telling the user we couldn't match.
 */
sealed interface AppLookup {
    /** Found a launcher package that matches the spoken query. */
    data class Match(val packageId: String) : AppLookup
    /** The query reduces to a generic media noun; safe to fall through
     *  to whatever's active. */
    data object Generic : AppLookup
    /** Specific spoken name that didn't resolve to any installed app. */
    data class NotFound(val spoken: String) : AppLookup
}

/**
 * Read-side facade over [InstalledAppDao]. Resolves a free-form
 * spoken phrase ("audible", "youtube music", "spotify") to a
 * specific launcher package id.
 *
 * Matching tiers, in order — first hit wins:
 *   1. exact normalized equality
 *   2. label prefix-starts-with query  (shortest label wins)
 *   3. label substring contains query  (shortest label wins)
 *   4. reverse contains — query substring contains label
 *      (catches "absorbed" → "Absorb")
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

    /** See the [AppLookup] doc for what each return value means. */
    suspend fun lookup(query: String): AppLookup {
        val normalized = normalizeForIndex(query)
        if (normalized.isEmpty()) return AppLookup.Generic
        if (GENERIC_NOUNS.contains(normalized)) return AppLookup.Generic
        // Strip leading generic articles / determiners that show up in
        // dictation but never appear in app labels: "the youtube" →
        // "youtube", "my spotify" → "spotify".
        val trimmed = stripLeadingArticles(normalized)
        if (trimmed.isEmpty() || GENERIC_NOUNS.contains(trimmed)) return AppLookup.Generic

        dao.findExact(trimmed)?.let {
            Log.d(TAG, "exact match: '$query' → ${it.label} (${it.packageId})")
            return AppLookup.Match(it.packageId)
        }
        dao.findByPrefix(trimmed)?.let {
            Log.d(TAG, "prefix match: '$query' → ${it.label} (${it.packageId})")
            return AppLookup.Match(it.packageId)
        }
        // Only fall to substring matches for queries ≥4 chars to avoid
        // tiny needles (e.g. "do") matching half the launcher.
        if (trimmed.length >= 4) {
            dao.findByContains(trimmed)?.let {
                Log.d(TAG, "contains match: '$query' → ${it.label} (${it.packageId})")
                return AppLookup.Match(it.packageId)
            }
            // Reverse direction: spoken needle contains the label.
            // Catches "absorbed" → "Absorb", "spotify music app" →
            // "Spotify", etc. Runs after forward-contains so a label
            // that fully contains the needle (more specific) beats one
            // that's merely contained-in the needle (less specific).
            dao.findByReverseContains(trimmed)?.let {
                Log.d(TAG, "reverse-contains match: '$query' → ${it.label} (${it.packageId})")
                return AppLookup.Match(it.packageId)
            }
        }
        Log.d(TAG, "no match for '$query' (normalized='$trimmed')")
        return AppLookup.NotFound(query.trim())
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
