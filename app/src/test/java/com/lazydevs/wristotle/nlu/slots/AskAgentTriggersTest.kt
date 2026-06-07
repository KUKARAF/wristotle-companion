// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.slots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAgentTriggersTest {

    // --- sanitise ----------------------------------------------------------

    @Test fun `sanitise splits on newlines AND commas`() {
        assertEquals(
            listOf("jarvis", "buddy", "alfred"),
            AskAgentTriggers.sanitise("jarvis\nbuddy, alfred"),
        )
    }

    @Test fun `sanitise lowercases and trims`() {
        assertEquals(
            listOf("jarvis", "buddy"),
            AskAgentTriggers.sanitise("  JARVIS  \n  Buddy  "),
        )
    }

    @Test fun `sanitise drops blanks and duplicates`() {
        assertEquals(
            listOf("jarvis"),
            AskAgentTriggers.sanitise("jarvis\n\n,jarvis,  ,JARVIS"),
        )
    }

    @Test fun `sanitise on empty input is empty list`() {
        assertEquals(emptyList<String>(), AskAgentTriggers.sanitise(""))
        assertEquals(emptyList<String>(), AskAgentTriggers.sanitise("   \n  ,  "))
    }

    // --- default subjects still work --------------------------------------

    @Test fun `default subjects route via routeRegex`() {
        val re = AskAgentTriggers.routeRegex(emptyList())
        listOf(
            "ask agent what's the weather",
            "ask claude what time is it",
            "hey assistant remind me at 5pm",
            "ask the ai for help",
        ).forEach { q ->
            assertNotNull("expected match for: $q", re.find(q))
        }
    }

    @Test fun `default subjects strip via stripRegex`() {
        val re = AskAgentTriggers.stripRegex(emptyList())
        assertEquals(
            "what's the weather",
            "ask agent what's the weather".replace(re, "").trim(),
        )
        assertEquals(
            "what time is it",
            "ask claude: what time is it".replace(re, "").trim(),
        )
    }

    // --- custom subjects --------------------------------------------------

    @Test fun `custom subject routes via routeRegex (verb form)`() {
        val re = AskAgentTriggers.routeRegex(listOf("jarvis", "buddy"))
        assertNotNull(re.find("ask jarvis what's the time"))
        assertNotNull(re.find("hey buddy what's the weather"))
    }

    @Test fun `custom subject routes via routeRegex (wake-word form)`() {
        // The natural assistant-name pattern — no "ask" verb needed.
        val re = AskAgentTriggers.routeRegex(listOf("jarvis", "buddy"))
        assertNotNull("bare jarvis", re.find("Jarvis what is my github profile?"))
        assertNotNull("bare buddy", re.find("buddy what's the weather"))
    }

    @Test fun `custom subject strips via stripRegex (verb form)`() {
        val re = AskAgentTriggers.stripRegex(listOf("jarvis"))
        assertEquals(
            "what's the time",
            "ask jarvis what's the time".replace(re, "").trim(),
        )
    }

    @Test fun `custom subject strips via stripRegex (wake-word form)`() {
        val re = AskAgentTriggers.stripRegex(listOf("jarvis"))
        assertEquals(
            "what is my github profile?",
            "Jarvis what is my github profile?".replace(re, "").trim(),
        )
        assertEquals(
            "what's the weather",
            "jarvis, what's the weather".replace(re, "").trim(),
        )
    }

    @Test fun `built-in subject is NOT wake-word matchable`() {
        // "agent" alone is too generic to bare-match — could be a noun in
        // an unrelated query. The verb form (`ask agent …`) still works.
        val re = AskAgentTriggers.routeRegex(extras = emptyList())
        assertNull("bare agent should NOT route", re.find("agent fix my bug"))
        assertNull("bare ai should NOT route", re.find("ai is interesting"))
        assertNotNull("verb form still works", re.find("ask agent what's the weather"))
    }

    @Test fun `custom subject is additive - defaults still strip`() {
        // Adding "jarvis" must not break "ask claude".
        val re = AskAgentTriggers.stripRegex(listOf("jarvis"))
        assertEquals(
            "what's the weather",
            "ask claude what's the weather".replace(re, "").trim(),
        )
    }

    // --- safety: regex metacharacters in user input -----------------------

    @Test fun `regex metacharacters in custom subjects do not crash compile`() {
        // The contract: a user typing regex metacharacters in the Settings
        // field can't blow up the next voice query. The match behaviour
        // depends on `\b` semantics — a trigger ending in a word char
        // matches cleanly; one ending in punctuation (e.g. `c++`) silently
        // doesn't match because there's no word/non-word transition
        // between `+` and the trailing space. Both shapes must just
        // compile without throwing.
        val re = AskAgentTriggers.routeRegex(listOf("c++", "a.b", "[brackets]"))
        // Word-char-ending trigger with a regex metachar matches literally:
        assertNotNull("a.b should match", re.find("ask a.b for help"))
        assertNull("a.b should NOT match aXb (metachar escaped, not greedy)", re.find("ask aXb for help"))
        // Defaults still work even with bad custom input:
        assertNotNull("defaults still active", re.find("ask agent for help"))
    }

    // --- non-triggers stay non-triggers -----------------------------------

    @Test fun `ordinary queries are not matched`() {
        val re = AskAgentTriggers.routeRegex(listOf("jarvis"))
        assertNull(re.find("call mom"))
        assertNull(re.find("set a timer for 10 minutes"))
        assertNull(re.find("what time is it"))
    }

    @Test fun `mid-sentence trigger word is not matched - anchored`() {
        val re = AskAgentTriggers.routeRegex(listOf("jarvis"))
        // The regex is `^\s*` anchored — a stray "ask jarvis" in the middle
        // of an otherwise unrelated query shouldn't fire.
        assertNull(re.find("call mom and ask jarvis later"))
    }
}