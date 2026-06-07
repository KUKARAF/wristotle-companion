// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.phone

import android.content.Context

/**
 * User-defined `spoken phrase → ContactRef` overrides for contact
 * resolution, so a Whisper mishear ("next" for "text") or a nickname
 * ("mom" → a specific contact) can be pinned to a real Contacts row.
 * Consulted first by [ContactsRepository.findContact] — an alias is an
 * explicit choice that beats the LIKE-percent matcher.
 *
 * Prefs-backed (not Room): tiny map of user data, no schema migrations,
 * and a Room DB would survive destructive scan rebuilds via Room's own
 * migration story which is more rigging than this map deserves. Phrases
 * are stored already normalised via [normalizePhrase] so [resolve] can
 * compare on the same key the spoken-contact slot is normalised against.
 * Serialization lives in the pure [ContactAliasCodec].
 *
 * Mirrors the shape of [com.lazydevs.wristotle.apps.AliasStore] —
 * deliberately the same API (`put` / `remove` / `all` / `resolve` /
 * `replaceAll`) so anyone reading both side-by-side sees the parallel.
 * The difference is the value type ([ContactRef] vs `String packageId`)
 * and the absence of a `retainInstalled`-style pruner — see
 * [ContactRef]'s doc for why dead links are surfaced in the UI rather
 * than silently dropped here.
 */
class ContactAliasStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    /** Inserts (or replaces) an alias from [phrase] (normalized internally) to [ref].
     *  No-op if the phrase is blank or any of the ref fields required by
     *  [ContactAliasCodec.decode] are blank. */
    fun put(phrase: String, ref: ContactRef) = synchronized(lock) {
        val key = normalizePhrase(phrase)
        if (key.isEmpty() || ref.lookupKey.isBlank() || ref.numberSnapshot.isBlank()) {
            return@synchronized
        }
        persist(load() + (key to ref))
    }

    fun remove(phrase: String) = synchronized(lock) {
        val key = normalizePhrase(phrase)
        val map = load()
        if (map.containsKey(key)) persist(map - key)
    }

    /** All aliases, `normalized phrase → ContactRef`. Iteration order
     *  matches the codec's sort (alphabetical by phrase). */
    fun all(): Map<String, ContactRef> = synchronized(lock) { load() }

    /**
     * Bulk replace — used by the backup importer + relink pass to commit
     * a merged / relinked map in one shot. Keys are not re-normalised
     * here; callers must provide already-normalised phrases (the manifest
     * stores them normalised because [put] normalises on the way in).
     */
    fun replaceAll(map: Map<String, ContactRef>) = synchronized(lock) {
        persist(map)
    }

    /** Resolve an already-normalized phrase to a [ContactRef], or null. */
    fun resolve(normalizedPhrase: String): ContactRef? =
        synchronized(lock) { load()[normalizedPhrase] }

    private fun load(): Map<String, ContactRef> =
        ContactAliasCodec.decode(prefs.getString(KEY_ALIASES, "") ?: "")

    private fun persist(map: Map<String, ContactRef>) {
        prefs.edit().putString(KEY_ALIASES, ContactAliasCodec.encode(map)).apply()
    }

    internal companion object {
        const val PREFS_NAME = "contact_aliases"
        const val KEY_ALIASES = "aliases"
    }
}

/**
 * Normalises an alias phrase for storage + lookup. Lowercase + trim +
 * collapse internal whitespace. Unlike the apps-side normaliser, this
 * preserves non-ASCII characters and punctuation — contact names can
 * be "José", "O'Brien", "Smith-Jones", and a stripped form would
 * surprise users when they typed the alias as it appears in Contacts.
 *
 * The spoken-contact value at lookup time comes from the slot
 * extractor's `contact` slot, which is already lowercased. Re-applying
 * `lowercase()` is idempotent + cheap; keep it so the helper is robust
 * if a caller passes a raw phrase from the UI form.
 */
internal fun normalizePhrase(text: String): String =
    text.lowercase()
        .replace(MULTI_WS, " ")
        .trim()

private val MULTI_WS = Regex("\\s+")