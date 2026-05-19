package com.lazydevs.wristotle.apps

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIndexTest {

    private fun app(pkg: String, label: String): InstalledApp = InstalledApp(
        packageId = pkg,
        label = label,
        normalizedLabel = normalizeForIndex(label),
        normalizedPackage = normalizeForPackageId(pkg),
        lastScannedAtMs = 0L,
    )

    private fun index(vararg installed: InstalledApp): AppIndex =
        AppIndex(FakeInstalledAppDao(installed.toList()))

    private fun lookup(idx: AppIndex, q: String): AppLookup =
        runBlocking { idx.lookup(q) }

    // --- Generic / empty -------------------------------------------

    @Test fun `empty query returns Generic`() {
        assertEquals(AppLookup.Generic, lookup(index(), ""))
    }

    @Test fun `whitespace-only query returns Generic`() {
        assertEquals(AppLookup.Generic, lookup(index(), "   "))
    }

    @Test fun `bare generic noun returns Generic`() {
        // Even when an app named "Music" is installed, the generic-noun
        // filter wins so "play music" stays a fall-through case.
        val idx = index(app("com.example.music", "Music"))
        assertEquals(AppLookup.Generic, lookup(idx, "music"))
    }

    @Test fun `leading article + generic noun returns Generic`() {
        assertEquals(AppLookup.Generic, lookup(index(), "the song"))
    }

    // --- Match tiers -----------------------------------------------

    @Test fun `exact match wins`() {
        val idx = index(app("com.spotify.music", "Spotify"))
        val r = lookup(idx, "spotify") as AppLookup.Match
        assertEquals("com.spotify.music", r.packageId)
    }

    @Test fun `leading article is stripped before matching`() {
        val idx = index(app("com.google.android.youtube", "YouTube"))
        val r = lookup(idx, "the youtube") as AppLookup.Match
        assertEquals("com.google.android.youtube", r.packageId)
    }

    @Test fun `prefix match picks shortest matching label`() {
        // Two apps share the "youtube" prefix; the shorter label is the
        // more specific match.
        val idx = index(
            app("com.google.android.youtube", "YouTube"),
            app("com.google.android.apps.youtube.music", "YouTube Music"),
        )
        val r = lookup(idx, "youtube") as AppLookup.Match
        assertEquals("com.google.android.youtube", r.packageId)
    }

    @Test fun `contains match for queries 4 chars or longer`() {
        val idx = index(app("com.audible.application", "Audible"))
        val r = lookup(idx, "dible") as AppLookup.Match
        assertEquals("com.audible.application", r.packageId)
    }

    @Test fun `short queries do not trigger substring matching`() {
        // "do" is 2 chars — too short to substring-match (would hit
        // half the launcher). Returns NotFound.
        val idx = index(app("com.audible.application", "Audible"))
        val r = lookup(idx, "do")
        assertTrue(r is AppLookup.NotFound)
    }

    @Test fun `reverse contains catches needle bigger than label`() {
        // Whisper mishear: user said "audible", Whisper transcribed
        // "absorbed". The label "absorb" is contained in "absorbed",
        // so reverse-contains catches it.
        val idx = index(app("com.barnabas.absorb", "Absorb"))
        val r = lookup(idx, "absorbed") as AppLookup.Match
        assertEquals("com.barnabas.absorb", r.packageId)
    }

    @Test fun `reverse contains picks longest label when multiple match`() {
        // "youtube music" contains both "youtube" and "youtube music"
        // (as labels). The longer label is the more specific match.
        val idx = index(
            app("com.google.android.youtube", "YouTube"),
            app("com.google.android.apps.youtube.music", "YouTube Music"),
        )
        val r = lookup(idx, "youtube music collection") as AppLookup.Match
        assertEquals("com.google.android.apps.youtube.music", r.packageId)
    }

    // --- Package-id fallback ---------------------------------------

    @Test fun `package-id contains match wins when label differs`() {
        // The motivating real-world case: launcher label is "YT Music"
        // but the package path contains "youtube.music". Spoken
        // "youtube music" doesn't match any label tier, but contains-
        // match against the normalized package "morphe youtube music"
        // resolves correctly.
        val idx = index(
            app("app.morphe.android.youtube", "YouTube"),
            app("app.morphe.android.apps.youtube.music", "YT Music"),
        )
        val r = lookup(idx, "youtube music") as AppLookup.Match
        assertEquals("app.morphe.android.apps.youtube.music", r.packageId)
    }

    @Test fun `label match still wins over package match within same tier`() {
        // If both the label and the package would match in the same
        // tier, the label hit takes precedence — labels are what the
        // user actually sees in their launcher.
        val idx = index(
            // Label "YT Music" doesn't match "youtube music" via any
            // tier; package "morphe.youtube.music" does (contains).
            app("app.morphe.android.apps.youtube.music", "YT Music"),
            // Label "YouTube Music" exact-matches "youtube music".
            app("com.example.fake", "YouTube Music"),
        )
        val r = lookup(idx, "youtube music") as AppLookup.Match
        assertEquals("com.example.fake", r.packageId)
    }

    @Test fun `package suffix strip drops trailing build-variant markers`() {
        // "com.spotify.music.release" → after stripping `com` (TLD) +
        // `release` (variant) → package tokens "spotify music".
        // Spoken "spotify music" should exact-match the package.
        val idx = index(app("com.spotify.music.release", "Spotify"))
        val r = lookup(idx, "spotify music") as AppLookup.Match
        assertEquals("com.spotify.music.release", r.packageId)
    }

    @Test fun `vendor name in package is NOT stripped`() {
        // "com.google.android.maps" → strip TLD `com` + middle `android`
        // → "google maps". User dictates "google maps" → exact match
        // via package. Keeping the vendor in is the safer default.
        val idx = index(app("com.google.android.maps", "Maps"))
        val r = lookup(idx, "google maps") as AppLookup.Match
        assertEquals("com.google.android.maps", r.packageId)
    }

    // --- Not found -------------------------------------------------

    @Test fun `unknown specific name returns NotFound carrying the spoken form`() {
        val idx = index(app("com.audible.application", "Audible"))
        val r = lookup(idx, "absolpt") as AppLookup.NotFound
        assertEquals("absolpt", r.spoken)
    }

    // --- count and latestScanAt pass through -----------------------

    @Test fun `count reflects DAO state`() {
        val idx = index(
            app("a.b.c", "App A"),
            app("d.e.f", "App B"),
        )
        assertEquals(2, runBlocking { idx.count() })
    }
}
