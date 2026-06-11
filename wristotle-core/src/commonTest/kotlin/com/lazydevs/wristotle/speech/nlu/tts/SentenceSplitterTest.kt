// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [splitTtsIntoSentences] — the sentence splitter driving
 * `TtsStreamer.speak`'s pipelined-sequential synth. Bug class to guard
 * against: wrong splits push time-to-first-audio up (or, worse, drop
 * sentences).
 */
class SentenceSplitterTest {

    @Test fun `empty input yields empty list`() {
        assertEquals(emptyList(), splitTtsIntoSentences(""))
        assertEquals(emptyList(), splitTtsIntoSentences("   \n\n   "))
    }

    @Test fun `short single sentence is not split`() {
        assertEquals(listOf("Hello there."), splitTtsIntoSentences("Hello there."))
    }

    @Test fun `period question exclamation all split`() {
        val out = splitTtsIntoSentences("One. Two? Three!")
        assertEquals(listOf("One.", "Two?", "Three!"), out)
    }

    @Test fun `newlines split — morning brief shape`() {
        // Morning Brief blocks use newlines, no terminating punctuation.
        // Without newline-as-boundary the whole brief is one synth, and
        // pipelining buys nothing.
        val out = splitTtsIntoSentences("Tasks: 2 pending\nReminders: 1\nWeather: sunny")
        assertEquals(listOf("Tasks: 2 pending", "Reminders: 1", "Weather: sunny"), out)
    }

    @Test fun `multiple consecutive newlines collapse to one boundary`() {
        val out = splitTtsIntoSentences("First.\n\n\nSecond.")
        assertEquals(listOf("First.", "Second."), out)
    }

    @Test fun `short first sentence skips fast-start split`() {
        // 30 chars — under FAST_START_MIN_LENGTH, even with a comma.
        val short = "Hi there, what's up today."
        assertTrue(short.length < FAST_START_MIN_LENGTH)
        assertEquals(listOf(short), splitTtsIntoSentences(short))
    }

    @Test fun `long first sentence with comma splits at the comma`() {
        // 73 chars, comma at position 38 (within MIN..MAX_PREFIX). Fast-start
        // saves 2-4 s of perceived latency by streaming the leading clause
        // while the rest of the sentence is still synth'ing.
        val long = "Hello and good morning to you, I hope your day is off to a great start."
        val out = splitTtsIntoSentences(long)
        assertEquals(2, out.size, "should split into leading clause + remainder")
        assertEquals("Hello and good morning to you,", out[0])
        assertEquals("I hope your day is off to a great start.", out[1])
    }

    @Test fun `long first sentence with NO comma in range is not split`() {
        // 80+ chars, NO comma. We can't split mid-word, so the whole sentence
        // stays as one chunk and we wait for full synth. Acceptable.
        val long = "This is a long sentence without any commas that we just need to speak normally."
        assertTrue(long.length > FAST_START_MIN_LENGTH)
        assertTrue(!long.substringBefore('.').contains(','))
        assertEquals(listOf(long), splitTtsIntoSentences(long))
    }

    @Test fun `comma too early skips fast-start`() {
        // Comma at position 2 — splitting would give a meaningless 2-char
        // first chunk. Below FAST_START_MIN_PREFIX so we skip.
        val s = "Hi, this is the rest of a sentence that's now long enough to trip the split heuristic."
        val out = splitTtsIntoSentences(s)
        assertEquals(listOf(s), out, "comma below MIN_PREFIX should NOT trigger fast-start")
    }

    @Test fun `comma too late skips fast-start`() {
        // Comma at position 95 — by then the leading clause IS the synth
        // we'd have done anyway, so fast-start saves nothing.
        val lead = "x".repeat(95)  // 95 chars before the comma
        val s = "$lead, and the rest doesn't matter."
        val out = splitTtsIntoSentences(s)
        assertEquals(listOf(s), out, "comma above MAX_PREFIX should NOT trigger fast-start")
    }

    @Test fun `fast-start only applies to FIRST sentence`() {
        // Sentence 1: short, no split. Sentence 2: long with comma — but
        // it's not the first, so it pipelines behind sentence 1's audio
        // and we don't bother splitting it.
        val s = "First sentence. " +
                "Second sentence has plenty of length to qualify, " +
                "but pipelining hides its synth time."
        val out = splitTtsIntoSentences(s)
        assertEquals(2, out.size)
        assertEquals("First sentence.", out[0])
        assertTrue(out[1].contains(","), "comma stays inside sentence 2")
    }

    @Test fun `trims whitespace from each sentence`() {
        val out = splitTtsIntoSentences("   One.   Two.    ")
        assertEquals(listOf("One.", "Two."), out)
    }
}
