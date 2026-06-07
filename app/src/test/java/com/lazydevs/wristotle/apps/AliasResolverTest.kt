// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AliasResolverTest {

    @Test fun `exact match wins`() {
        val map = mapOf("audiobook" to "pkg.audible", "spotify" to "pkg.spotify")
        assertEquals("pkg.audible", resolve(map, "audiobook"))
        assertEquals("pkg.spotify", resolve(map, "spotify"))
    }

    @Test fun `stored compound resolves spoken split`() {
        // User stored "audiobook"; STT split it to "audio book".
        val map = mapOf("audiobook" to "pkg.audible")
        assertEquals("pkg.audible", resolve(map, "audio book"))
    }

    @Test fun `stored split resolves spoken compound`() {
        // User stored "audio book"; STT joined it to "audiobook".
        val map = mapOf("audio book" to "pkg.audible")
        assertEquals("pkg.audible", resolve(map, "audiobook"))
    }

    @Test fun `both forms stored — direct match wins for each spoken form`() {
        // User intentionally pinned different apps to the two spellings.
        val map = mapOf(
            "audiobook" to "pkg.audible",
            "audio book" to "pkg.libby",
        )
        assertEquals("pkg.audible", resolve(map, "audiobook"))
        assertEquals("pkg.libby", resolve(map, "audio book"))
    }

    @Test fun `unrelated phrase still returns null`() {
        val map = mapOf("audiobook" to "pkg.audible")
        assertNull(resolve(map, "spotify"))
        assertNull(resolve(map, "audio bok")) // typo — not a compound variant
    }

    @Test fun `empty map returns null for any query`() {
        assertNull(resolve(emptyMap(), "anything"))
        assertNull(resolve(emptyMap(), ""))
    }

    @Test fun `multi-word compound is also tolerated`() {
        // "audio book player" ↔ "audiobookplayer" — same fallback applies
        // for arbitrary numbers of joined / split words.
        val map = mapOf("audiobookplayer" to "pkg.smartabp")
        assertEquals("pkg.smartabp", resolve(map, "audio book player"))
    }

    private fun resolve(map: Map<String, String>, phrase: String): String? =
        resolveAliasWithCompoundFallback(map, phrase)
}