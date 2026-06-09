// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import com.lazydevs.wristotle.speech.nlu.slots.*

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSlotsTest {

    private fun extract(query: String): String? = runBlocking {
        CallSlots().extract(query)["contact"] as String?
    }

    @Test fun `bare call verb plus name`() {
        assertEquals("mom", extract("call mom"))
    }

    @Test fun `dial verb`() {
        assertEquals("john", extract("dial john"))
    }

    @Test fun `ring verb plus possessive filler`() {
        assertEquals("mom", extract("ring my mom"))
    }

    @Test fun `give NAME a call pattern`() {
        // "give" and "a" both in the FILLERS set; the verb sits at the end.
        assertEquals("john", extract("give john a call"))
    }

    @Test fun `polite preface gets stripped`() {
        assertEquals("dad", extract("could you call dad"))
    }

    @Test fun `trailing 'for me' gets stripped`() {
        assertEquals("alice", extract("call alice for me"))
    }

    @Test fun `trailing 'back' gets stripped`() {
        assertEquals("bob", extract("call bob back"))
    }

    @Test fun `trailing emphasis run gets stripped`() {
        // The failure case from the device: "John, yes, yes, yes." was
        // routed to Call but contact lookup got the entire mess. Now the
        // trailing emphasis is dropped first.
        assertEquals("john", extract("call john, yes, yes, yes"))
    }

    @Test fun `multi-word contact name survives`() {
        assertEquals("aunt mary", extract("call aunt mary"))
    }

    @Test fun `non-name remainder still surfaces as contact for handler to reject`() {
        // "me" isn't in the filler list, so it survives extraction. The
        // contact-not-found message in production comes from the handler's
        // ContactsRepository lookup, not from the extractor.
        assertEquals("me", extract("call me"))
    }
}