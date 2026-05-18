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
}
