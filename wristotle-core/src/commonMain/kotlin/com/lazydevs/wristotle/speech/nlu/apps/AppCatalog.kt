// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.apps

/**
 * Three-way result distinguishing "user named an app we found",
 * "user said a generic media noun" ("play the song"), and "user
 * named something we don't recognise" ("play absolpt"). The
 * distinction matters because handlers should fall through to the
 * active session ONLY for generic phrasings — not for unrecognised
 * specific names, where silently acting on the wrong app would be
 * worse than telling the user we couldn't match.
 *
 * R4 batch 7 — lifted from :app/apps.
 */
sealed interface AppLookup {
    /** Found a launcher package that matches the spoken query. */
    data class Match(val packageId: String) : AppLookup

    /** The query reduces to a generic media noun; safe to fall through. */
    data object Generic : AppLookup

    /** Specific spoken name that didn't resolve to any installed app. */
    data class NotFound(val spoken: String) : AppLookup
}

/**
 * The lookup surface OpenAppHandler + the media handlers consume. The
 * Android impl is `AppIndex` (Room-backed); an iOS impl would wrap
 * `LSApplicationWorkspace` (private API) or the per-installed-handler
 * registration the user maintains.
 *
 * Kept narrow on purpose — the OpenAppHandler / media handlers only
 * need [count] (to detect an unscanned index) and [lookup] (to resolve
 * spoken name → packageId).
 */
interface AppCatalog {
    /** Number of indexed apps. Zero means the user hasn't run the scan. */
    suspend fun count(): Int

    /** Resolve a free-form spoken phrase to an installed package. */
    suspend fun lookup(query: String): AppLookup
}
