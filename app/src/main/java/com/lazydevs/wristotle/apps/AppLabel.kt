// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

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

/**
 * Derive a lookup key from a package id by stripping segments that
 * are noise from a voice-command perspective and normalising what's
 * left. Lets queries like "youtube music" resolve to apps whose
 * launcher label is "YT Music" but whose package path contains
 * `.youtube.music.` (e.g. `app.morphe.android.apps.youtube.music`).
 *
 * Strip rules (conservative to avoid over-matching):
 *
 *   - **Leading TLD-style segments** — `com`, `org`, `app`, `io`,
 *     `net`, `de`, `me`, `tv`, `co`, `dev`, `eu`, `fr`, `it`, `nl`,
 *     `us`, `ai`, `dk`, `jp`, `br`, `uk`, `ru`, `cn`. These are the
 *     reverse-DNS roots and have zero semantic value.
 *   - **Middle noise** anywhere in the chain — `android`, `apps`,
 *     `mobile`, `client`. Common path components no one speaks.
 *   - **Trailing build-variant markers** — `app`, `apk`, `release`,
 *     `beta`, `debug`, `alpha`, `prod`, `internal`, `free`, `paid`,
 *     `lite`, `bundle`. Mark the channel, not the app identity.
 *
 * Vendor names (google, samsung, motorola, etc.) are NOT stripped —
 * for "Samsung Notes" or "Google Drive" the vendor IS what the user
 * says. Leaving them in is the safer default; the four-tier matcher
 * + the AppIndex's generic-noun denylist handle the edge cases.
 */
internal fun normalizeForPackageId(packageId: String): String {
    val parts = packageId.split('.').toMutableList()
    while (parts.isNotEmpty() && parts.first() in PACKAGE_LEADING_STRIP) parts.removeAt(0)
    parts.removeAll { it in PACKAGE_ANYWHERE_STRIP }
    while (parts.isNotEmpty() && parts.last() in PACKAGE_TRAILING_STRIP) parts.removeAt(parts.size - 1)
    return normalizeForIndex(parts.joinToString(" "))
}

private val NON_ALNUM_OR_SPACE = Regex("[^a-z0-9 ]")
private val MULTI_SPACE = Regex("\\s+")

private val PACKAGE_LEADING_STRIP = setOf(
    "com", "org", "app", "io", "net", "de", "me", "tv", "co", "dev",
    "eu", "fr", "it", "nl", "us", "ai", "dk", "jp", "br", "uk", "ru", "cn",
)
private val PACKAGE_ANYWHERE_STRIP = setOf(
    "android", "apps", "mobile", "client",
)
private val PACKAGE_TRAILING_STRIP = setOf(
    "app", "apk", "release", "beta", "debug", "alpha", "prod",
    "internal", "free", "paid", "lite", "bundle",
)