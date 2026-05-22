package com.lazydevs.wristotle.apps

import com.lazydevs.wristotle.logging.WristotleLog as Log

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
 * Matching tiers, in order — first hit wins. Within each tier, the
 * label column is tried first; only when it misses does the package
 * column get a turn. Label hits are preferred because they reflect
 * what the user actually sees in the launcher; the package column
 * is the cleanup pass for cases where the launcher name and the
 * common name differ ("YT Music" launcher label vs `youtube.music`
 * in the package id).
 *
 *   1. exact normalized equality              (label, then package)
 *   2. prefix-starts-with query               (label, then package)
 *   3. substring contains query               (label, then package)
 *   4. reverse contains — query contains key  (label, then package)
 *
 * Generic media nouns are filtered out before any lookup — without
 * the denylist, "play music" / "play that song" would pull in any
 * app whose name happens to contain "music" or "song" (Apple Music,
 * Google Play Music, etc.) when the user really meant "resume on
 * the active session". The denylist makes [MediaPlayHandler]'s
 * fallback-to-active-session path keep working for those phrasings.
 */
class AppIndex(
    private val dao: InstalledAppDao,
    /** User-defined `normalized phrase → packageId` overrides (see AliasStore).
     *  Resolved before everything else. Default = no aliases (keeps tests +
     *  callers that don't care simple). */
    private val aliasResolver: (String) -> String? = { null },
) {

    suspend fun count(): Int = dao.count()
    suspend fun latestScanAt(): Long? = dao.latestScanAt()

    /** See the [AppLookup] doc for what each return value means. */
    suspend fun lookup(query: String): AppLookup {
        val normalized = normalizeForIndex(query)
        if (normalized.isEmpty()) return AppLookup.Generic

        // Alias overrides win over EVERYTHING — the generic-noun denylist and
        // all fuzzy tiers — because an alias is an explicit user choice (even
        // "music" → Spotify if they set it). Try the full phrase, then the
        // article-stripped form ("the podcasts" → "podcasts"). Honor it only
        // when the target is still installed; otherwise fall through to fuzzy.
        val trimmed = stripLeadingArticles(normalized)
        resolveAlias(normalized)?.let { return it }
        if (trimmed != normalized) resolveAlias(trimmed)?.let { return it }

        if (GENERIC_NOUNS.contains(normalized)) return AppLookup.Generic
        if (trimmed.isEmpty() || GENERIC_NOUNS.contains(trimmed)) return AppLookup.Generic

        // Tier 1 — exact
        dao.findExact(trimmed)?.let { return matched("exact label", query, it) }
        dao.findExactByPackage(trimmed)?.let { return matched("exact package", query, it) }
        // Tier 2 — prefix
        dao.findByPrefix(trimmed)?.let { return matched("prefix label", query, it) }
        dao.findByPrefixOfPackage(trimmed)?.let { return matched("prefix package", query, it) }
        // Tier 3 + 4 are restricted to queries ≥4 chars to avoid tiny
        // needles (e.g. "do") matching half the launcher.
        if (trimmed.length >= 4) {
            dao.findByContains(trimmed)?.let { return matched("contains label", query, it) }
            dao.findByContainsInPackage(trimmed)?.let { return matched("contains package", query, it) }
            // Reverse direction: spoken needle contains the label.
            // Catches "absorbed" → "Absorb", "spotify music app" →
            // "Spotify", etc. Runs after forward-contains so a label
            // that fully contains the needle (more specific) beats one
            // that's merely contained-in the needle (less specific).
            dao.findByReverseContains(trimmed)?.let { return matched("reverse-contains label", query, it) }
            dao.findByReverseContainsOfPackage(trimmed)?.let { return matched("reverse-contains package", query, it) }
        }
        Log.d(TAG, "no match for '$query' (normalized='$trimmed')")
        return AppLookup.NotFound(query.trim())
    }

    /** Resolve an alias for [phrase] to an installed package, or null if there's
     *  no alias OR the aliased app is no longer installed (so the caller falls
     *  through to the fuzzy tiers). */
    private suspend fun resolveAlias(phrase: String): AppLookup.Match? {
        val pkg = aliasResolver(phrase) ?: return null
        if (dao.findByPackageId(pkg) == null) {
            Log.d(TAG, "alias '$phrase' → $pkg but not installed; ignoring")
            return null
        }
        Log.d(TAG, "alias match: '$phrase' → $pkg")
        return AppLookup.Match(pkg)
    }

    private fun matched(tier: String, query: String, app: InstalledApp): AppLookup.Match {
        Log.d(TAG, "$tier match: '$query' → ${app.label} (${app.packageId})")
        return AppLookup.Match(app.packageId)
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
