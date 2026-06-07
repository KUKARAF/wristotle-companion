// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppendNoteSlotsTest {

    private fun body(query: String): String? = runBlocking {
        AppendNoteSlots().extract(query)["body"] as String?
    }

    @Test fun `add to previous notes is stripped`() {
        assertEquals("Meeting moved to five", body("add to previous notes meeting moved to five"))
    }

    @Test fun `add to my previous note is stripped`() {
        assertEquals("Speaker is bob", body("add to my previous note speaker is bob"))
    }

    @Test fun `add to the last note is stripped`() {
        assertEquals("Alex is bringing snacks", body("add to the last note alex is bringing snacks"))
    }

    @Test fun `add to latest notes is stripped`() {
        assertEquals("Room changed", body("add to latest notes room changed"))
    }

    @Test fun `append to my note is stripped`() {
        assertEquals("Tracking number is 1234", body("append to my note tracking number is 1234"))
    }

    @Test fun `append alone is stripped`() {
        assertEquals("Door code is 9876", body("append door code is 9876"))
    }

    @Test fun `append with colon`() {
        assertEquals("Meeting is in room c", body("append: meeting is in room c"))
    }

    @Test fun `append with trailing period (whisper auto-punctuation)`() {
        assertEquals("Bring extra chairs", body("Append. Bring extra chairs"))
    }

    @Test fun `add to the previous note with that connector`() {
        assertEquals("Recipe needs salt", body("add to the previous note that recipe needs salt"))
    }

    // Whisper consistently mishears "append" as "amend" — caught when a Core
    // Devices alpha-tester said "append to the notes" and the transcript
    // came back as "Amend to the notes: ...". `amend` is unambiguous (can
    // only modify something that exists) so it routes to AppendNote.
    @Test fun `amend with colon`() {
        assertEquals("This is a test", body("Amend to the notes: this is a test"))
    }

    @Test fun `amend alone is stripped`() {
        assertEquals("Door code is 9876", body("amend door code is 9876"))
    }

    @Test fun `amend my last note is stripped`() {
        assertEquals("Speaker is bob", body("amend my last note speaker is bob"))
    }
}