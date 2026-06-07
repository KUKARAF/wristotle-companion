// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAppSlotsTest {

    private fun extract(query: String): Map<String, Any> =
        runBlocking { OpenAppSlots().extract(query) }

    @Test fun `open with app name returns app slot`() {
        assertEquals("spotify", extract("open spotify")["app"])
    }

    @Test fun `launch with app name returns app slot`() {
        assertEquals("audible", extract("launch audible")["app"])
    }

    @Test fun `bare open returns empty map`() {
        assertTrue(extract("open").isEmpty())
    }

    @Test fun `the and app fillers are stripped`() {
        assertEquals("camera", extract("open the camera app")["app"])
    }

    @Test fun `multi-word fire up is consumed as a single verb`() {
        // Verify the matcher consumes "fire up" as a unit and doesn't
        // leave "up" behind for downstream string ops.
        assertEquals("settings", extract("fire up settings")["app"])
    }

    @Test fun `multi-word switch to is consumed as a single verb`() {
        assertEquals("messages", extract("switch to messages")["app"])
    }

    @Test fun `multi-word go to is consumed as a single verb`() {
        assertEquals("play store", extract("go to the play store")["app"])
    }

    @Test fun `case and trailing punctuation normalised`() {
        assertEquals("chrome", extract("Open CHROME.")["app"])
    }

    @Test fun `load and show variants are recognised`() {
        assertEquals("instagram", extract("load instagram")["app"])
        assertEquals("calendar", extract("show calendar")["app"])
    }
}