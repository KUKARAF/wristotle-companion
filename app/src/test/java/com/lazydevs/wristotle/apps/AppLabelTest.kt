// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLabelTest {

    @Test fun `lowercases input`() {
        assertEquals("youtube", normalizeForIndex("YouTube"))
    }

    @Test fun `strips colon and collapses extra whitespace`() {
        // "Audible: Audiobooks" → strip non-alnum → "audible  audiobooks"
        // → collapse → "audible audiobooks".
        assertEquals("audible audiobooks", normalizeForIndex("Audible: Audiobooks"))
    }

    @Test fun `em-dash and other punctuation reduce to a single space`() {
        assertEquals("youtube music", normalizeForIndex("YouTube — Music"))
    }

    @Test fun `leading and trailing whitespace trimmed`() {
        assertEquals("spotify", normalizeForIndex("  Spotify  "))
    }

    @Test fun `digits in label are preserved`() {
        assertEquals("app42", normalizeForIndex("App42"))
    }

    @Test fun `empty string normalises to empty string`() {
        assertEquals("", normalizeForIndex(""))
    }

    @Test fun `only-punctuation collapses to empty`() {
        assertEquals("", normalizeForIndex("!@#$%"))
    }

    // --- normalizeForPackageId -------------------------------------

    @Test fun `package strip drops leading TLD-style segment`() {
        assertEquals("spotify music", normalizeForPackageId("com.spotify.music"))
    }

    @Test fun `package strip drops middle android and apps segments`() {
        assertEquals("morphe youtube music",
            normalizeForPackageId("app.morphe.android.apps.youtube.music"))
    }

    @Test fun `package strip drops trailing build-variant segment`() {
        assertEquals("audiobookshelf", normalizeForPackageId("com.audiobookshelf.app"))
    }

    @Test fun `package strip drops trailing release marker`() {
        assertEquals("mm20 launcher2", normalizeForPackageId("de.mm20.launcher2.release"))
    }

    @Test fun `package strip preserves vendor names like google`() {
        // We intentionally don't strip "google" — for apps like
        // "Google Maps" or "Google Drive" the vendor IS what the
        // user speaks.
        assertEquals("google youtube", normalizeForPackageId("com.google.android.youtube"))
    }

    @Test fun `package strip handles multi-tier prefixes`() {
        // org.X then strip
        assertEquals("telegram messenger", normalizeForPackageId("org.telegram.messenger"))
    }

    @Test fun `package strip leaves single-segment packages alone after TLD removal`() {
        // Pathological: "com.foo" → just "foo".
        assertEquals("foo", normalizeForPackageId("com.foo"))
    }

    @Test fun `package strip collapses to empty on pathological inputs`() {
        // All segments are noise — nothing left.
        assertEquals("", normalizeForPackageId("com.android.apps.app"))
    }
}