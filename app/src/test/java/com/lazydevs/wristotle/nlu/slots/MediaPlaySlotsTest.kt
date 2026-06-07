// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaySlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { MediaPlaySlots().extract(query) }

    @Test fun `play with app name returns app slot`() {
        assertEquals("spotify", extract("play spotify")["app"])
    }

    @Test fun `resume with app name returns app slot`() {
        assertEquals("audible", extract("resume audible")["app"])
    }

    @Test fun `bare play returns empty map`() {
        val result = extract("play")
        assertTrue(result.isEmpty())
    }

    @Test fun `play with only filler words returns empty map`() {
        // "play the app" → strips "play", "the", "app" → empty body.
        // The handler's Fallback branch takes over.
        assertTrue(extract("play the app").isEmpty())
    }

    @Test fun `play music leaves music as body — generic-noun handling lives in AppIndex`() {
        // MediaPlaySlots itself doesn't know that "music" is generic;
        // it just returns whatever's left after stripping verbs+fillers.
        // AppIndex.lookup is the one that collapses "music" → Generic.
        assertEquals("music", extract("play music")["app"])
    }

    @Test fun `play on spotify strips the on filler`() {
        assertEquals("spotify", extract("play on spotify")["app"])
    }

    @Test fun `trailing punctuation is trimmed`() {
        assertEquals("youtube", extract("play youtube.")["app"])
    }

    @Test fun `mixed case query is lowercased`() {
        assertEquals("spotify", extract("Play SPOTIFY")["app"])
    }

    @Test fun `app slot absent when query has no verb at all`() {
        // No play verb → nothing gets stripped. Body becomes the full query.
        // The classifier should never route a verbless query here, but the
        // extractor stays defensive.
        assertEquals("spotify", extract("spotify")["app"])
    }

    @Test fun `start playing variant is recognised as a verb`() {
        assertEquals("audible", extract("start playing audible")["app"])
    }

    @Test fun `body slot is never null when present`() {
        assertNull(extract("play")["app"])
    }
}