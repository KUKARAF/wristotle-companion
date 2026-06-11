// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.tts

/**
 * Splits a TTS reply into individual sentences for pipelined synthesis.
 *
 * - Standard boundaries: `.!?` followed by whitespace, OR a newline.
 *   Multiple newlines collapse to one boundary so morning-brief blocks
 *   ("Tasks:\n- foo\n- bar") split cleanly.
 * - First-sentence fast-start: when the FIRST sentence is long enough
 *   that splitting it would meaningfully save synth time AND there's a
 *   comma at a sane prefix length, split off the leading clause as its
 *   own pipeline unit. Trades a tiny intonation seam at that comma for
 *   2–4 seconds of perceived latency (we start streaming the leading
 *   clause while the rest of the first sentence is still being synth'd).
 *   Only applies to the first sentence — subsequent sentences pipeline
 *   behind playback so their synth time is already hidden.
 *
 * Pure logic, no I/O. Lives in commonMain so the splitter is testable
 * from commonTest and runs identically on Android + iOS.
 */
fun splitTtsIntoSentences(
    text: String,
    minLengthForFastStart: Int = FAST_START_MIN_LENGTH,
    minPrefix: Int = FAST_START_MIN_PREFIX,
    maxPrefix: Int = FAST_START_MAX_PREFIX,
): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val base = trimmed.split(SENTENCE_SPLIT)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (base.isEmpty()) return base

    val first = base[0]
    if (first.length <= minLengthForFastStart) return base
    val commaIdx = first.indexOf(',')
    if (commaIdx !in minPrefix..maxPrefix) return base
    val firstChunk = first.substring(0, commaIdx + 1)
    val rest = first.substring(commaIdx + 1).trim()
    return listOf(firstChunk, rest) + base.drop(1)
}

/** Standard sentence-boundary regex used by [splitTtsIntoSentences].
 *  Hoisted to a top-level constant so each call doesn't pay the
 *  ~100 µs regex-compilation cost. */
internal val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+|\\n+")

/** A first sentence shorter than this skips the fast-start split —
 *  the fast-start latency saving isn't meaningful below ~60 chars. */
const val FAST_START_MIN_LENGTH = 60

/** Don't split off a leading clause shorter than this (avoids splitting
 *  on a comma 5 chars in, which gives a meaningless tiny first chunk). */
const val FAST_START_MIN_PREFIX = 15

/** Don't split off a leading clause longer than this (the fast-start
 *  saving evaporates once the leading clause is itself a full
 *  synth-worth of audio). */
const val FAST_START_MAX_PREFIX = 80
