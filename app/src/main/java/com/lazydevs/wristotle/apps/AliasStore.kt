package com.lazydevs.wristotle.apps

import android.content.Context

/**
 * User-defined `spoken phrase → packageId` overrides for app resolution, so a
 * Whisper mishear ("adobe" for "audible") or a nickname ("podcasts" → Pocket
 * Casts) can be pinned to a specific app. Consulted first by [AppIndex.lookup]
 * — an alias is an explicit choice that beats the fuzzy matchers.
 *
 * Prefs-backed (not Room) on purpose: it's a tiny map of *user data*, and the
 * app-index DB uses destructive migration (safe for the regenerable scan cache,
 * but it would wipe aliases on a schema bump). Phrases are stored already
 * normalized via [normalizeForIndex] so [resolve] can match the same key the
 * AppIndex computes. Serialization lives in the pure [AliasCodec].
 */
class AliasStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Maps [phrase] (normalized internally) to [packageId]. Overwrites any
     *  existing alias for the same phrase. No-op if either is blank. */
    fun put(phrase: String, packageId: String) = synchronized(lock) {
        val key = normalizeForIndex(phrase)
        if (key.isEmpty() || packageId.isBlank()) return@synchronized
        persist(load() + (key to packageId))
    }

    fun remove(phrase: String) = synchronized(lock) {
        val key = normalizeForIndex(phrase)
        val map = load()
        if (map.containsKey(key)) persist(map - key)
    }

    /** All aliases, `normalized phrase → packageId`. */
    fun all(): Map<String, String> = synchronized(lock) { load() }

    /**
     * Bulk replace — used by the backup importer to commit a merged map in
     * one shot. Keys are not re-normalised here; the importer is responsible
     * for providing already-normalised phrases (the manifest stores them
     * normalised because [put] normalised on the way out).
     */
    fun replaceAll(map: Map<String, String>) = synchronized(lock) {
        persist(map)
    }

    /** Resolve an already-normalized phrase to a packageId, or null. */
    fun resolve(normalizedPhrase: String): String? = synchronized(lock) { load()[normalizedPhrase] }

    /** Drops aliases whose target is no longer installed. Call after an app
     *  re-scan so dangling aliases don't accumulate. */
    fun retainInstalled(installedPackageIds: Set<String>) = synchronized(lock) {
        val map = load()
        val kept = map.filterValues { it in installedPackageIds }
        if (kept.size != map.size) persist(kept)
    }

    private fun load(): Map<String, String> =
        AliasCodec.decode(prefs.getString(KEY_ALIASES, "") ?: "")

    private fun persist(map: Map<String, String>) {
        prefs.edit().putString(KEY_ALIASES, AliasCodec.encode(map)).apply()
    }

    private companion object {
        const val PREFS_NAME = "app_aliases"
        const val KEY_ALIASES = "aliases"
    }
}
