// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSlotsTest {

    private fun body(query: String): String? = runBlocking {
        NoteSlots().extract(query)["body"] as String?
    }

    @Test fun `note colon prefix is stripped`() {
        assertEquals("Pick up milk", body("note: pick up milk"))
    }

    @Test fun `note prefix without colon`() {
        assertEquals("Pick up milk", body("note pick up milk"))
    }

    // Whisper often hears "notes" (plural) — both bare prefix and compound
    // forms ("make a notes", "save notes", "add to my notes") must strip too.
    @Test fun `notes plural prefix is stripped`() {
        assertEquals("This is a test", body("notes this is a test"))
    }

    @Test fun `notes plural with colon`() {
        assertEquals("Buy bread", body("notes: buy bread"))
    }

    // Whisper auto-punctuates: "notes" at the start often comes in as
    // "Notes." followed by a sentence. The strip must tolerate trailing
    // sentence-ending punctuation on the lead-in.
    @Test fun `notes with trailing period is stripped`() {
        assertEquals("This is the second message", body("Notes. This is the second message"))
    }

    @Test fun `note with trailing period is stripped`() {
        assertEquals("Pick up milk", body("Note. Pick up milk"))
    }

    @Test fun `save notes plural is stripped`() {
        assertEquals("Account number is on the desk", body("save notes account number is on the desk"))
    }

    @Test fun `make a note that is stripped`() {
        assertEquals("Meeting moved to four", body("make a note that meeting moved to four"))
    }

    @Test fun `make a note to is stripped`() {
        assertEquals("Buy batteries", body("make a note to buy batteries"))
    }

    @Test fun `remember that is stripped`() {
        assertEquals("Wifi password changed", body("remember that wifi password changed"))
    }

    @Test fun `jot down is stripped`() {
        assertEquals("Parking is b12", body("jot down parking is b12"))
    }

    @Test fun `jot this down is stripped`() {
        assertEquals("Account number is on the desk", body("jot this down account number is on the desk"))
    }

    @Test fun `write down is stripped`() {
        assertEquals("Door code is 1234", body("write down door code is 1234"))
    }

    @Test fun `for my notes is stripped`() {
        assertEquals("Conference room is on the third floor", body("for my notes conference room is on the third floor"))
    }

    @Test fun `add to my notes is stripped`() {
        assertEquals("Cat is allergic to chicken", body("add to my notes cat is allergic to chicken"))
    }

    @Test fun `noted is stripped`() {
        assertEquals("Recipe needs more salt", body("noted recipe needs more salt"))
    }

    @Test fun `body is capitalized`() {
        // First letter of remaining body becomes uppercase regardless of input.
        assertEquals("Hello world", body("note hello world"))
    }

    @Test fun `note mentioned mid-sentence is not a prefix strip`() {
        // The lead-in only fires when anchored at start.
        assertEquals("I left a note on the desk", body("i left a note on the desk"))
    }
}