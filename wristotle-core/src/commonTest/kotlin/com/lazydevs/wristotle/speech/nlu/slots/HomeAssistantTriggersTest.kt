// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.slots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HomeAssistantTriggers] — the strip/route regexes that both
 * [HomeAssistantSlots] and WatchHintRefiner depend on. Guards the two shapes
 * (verb form for all subjects, bare wake-word for customs), the optional
 * "to" connector, and that the built-in subjects don't collide with plain
 * speech.
 */
class HomeAssistantTriggersTest {

    private fun route(q: String, extras: List<String> = emptyList()) =
        HomeAssistantTriggers.routeRegex(extras).containsMatchIn(q)

    private fun strip(q: String, extras: List<String> = emptyList()) =
        q.replace(HomeAssistantTriggers.stripRegex(extras), "").trim()

    @Test fun route_matchesDefaultSubjectsAllVerbs() {
        assertTrue(route("hey home assistant turn off the lights"))
        assertTrue(route("home assistant set the thermostat to 20"))
        assertTrue(route("tell home assistant to lock the door"))
        assertTrue(route("ask home assistant is the door open"))
        assertTrue(route("hey homeassistant good night"))
        assertTrue(route("hey hass what's the temperature"))
    }

    @Test fun route_doesNotMatchAskAgentSubjects() {
        // "assistant" alone is AskAgent's; HA needs "home assistant"/"hass".
        assertFalse(route("hey assistant what's the weather"))
        assertFalse(route("ask agent what is the capital of france"))
    }

    @Test fun route_doesNotHijackPlainSpeech() {
        assertFalse(route("turn off the lights"))
        assertFalse(route("what's the temperature in the bedroom"))
    }

    @Test fun route_matchesCustomWakeWord() {
        assertTrue(route("jarvis turn off the lights", extras = listOf("jarvis")))
        // Unregistered custom word does not match.
        assertFalse(route("alfred turn off the lights", extras = listOf("jarvis")))
    }

    @Test fun strip_removesVerbLeadInAndOptionalTo() {
        assertEquals("turn off the lights", strip("hey home assistant turn off the lights"))
        assertEquals("lock the door", strip("tell home assistant to lock the door"))
        assertEquals("is the garage open", strip("ask home assistant is the garage open"))
        assertEquals("start the vacuum", strip("home assistant start the vacuum"))
    }

    @Test fun strip_customWakeWord() {
        assertEquals(
            "turn on the lamp",
            strip("Jarvis turn on the lamp", extras = listOf("jarvis")),
        )
    }

    @Test fun sanitise_normalisesAndDedupes() {
        assertEquals(
            listOf("jarvis", "computer"),
            HomeAssistantTriggers.sanitise("Jarvis, computer\njarvis , "),
        )
    }
}
