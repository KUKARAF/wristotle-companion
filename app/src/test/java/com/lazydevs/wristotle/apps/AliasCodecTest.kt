// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasCodecTest {

    @Test fun emptyRoundTrips() {
        assertTrue(AliasCodec.decode("").isEmpty())
        assertEquals("", AliasCodec.encode(emptyMap()))
    }

    @Test fun singleEntryRoundTrips() {
        val m = mapOf("adobe" to "com.audible.application")
        assertEquals(m, AliasCodec.decode(AliasCodec.encode(m)))
    }

    @Test fun multipleEntriesRoundTrip() {
        val m = mapOf(
            "adobe" to "com.audible.application",
            "podcasts" to "au.com.shiftyjelly.pocketcasts",
            "music" to "com.spotify.music",
        )
        assertEquals(m, AliasCodec.decode(AliasCodec.encode(m)))
    }

    @Test fun phraseWithSpacesSurvives() {
        val m = mapOf("my podcasts" to "au.com.shiftyjelly.pocketcasts")
        assertEquals(m, AliasCodec.decode(AliasCodec.encode(m)))
    }

    @Test fun malformedLinesAreSkipped() {
        // A line with no tab (no value) is dropped, not turned into a ghost.
        val raw = "adobe\tcom.audible.application\njunkwithnotab\n\tnopkg"
        assertEquals(mapOf("adobe" to "com.audible.application"), AliasCodec.decode(raw))
    }
}