// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.parsing

import kotlinx.datetime.Instant

/**
 * Parses a natural-language voice query into a wall-clock [Instant].
 *
 * The Android implementation wraps prettytime-nlp + the Wristotle word-form
 * normalizer; the iOS implementation will wrap whatever's the best
 * iOS-native equivalent (Foundation's `NSDataDetector` + a custom
 * normalizer, or a hand-rolled mini parser — TBD when iOS port starts).
 *
 * Returns null when the query carries no parseable time clause. The slot
 * extractors that depend on this (CalendarSlots, CancelAlarmSlots,
 * CreateEventSlots, ReminderSlots, SetAlarmSlots) treat null as "user
 * didn't say a time" and either default it or refuse the slot.
 *
 * R2 batch 4: this interface lifted the parseTime() top-level function
 * out of :app/handlers/ so the slot extractors above could move to
 * :speech-nlu commonMain. The PrettyTime-backed JVM impl stays in :app.
 */
interface TimeParser {
    fun parse(query: String): ParsedTime?
}

/**
 * Parser output. `instant` is the wall-clock point the user named (or
 * the relative offset the parser resolved). `matchedText` is the literal
 * substring of the query the parser consumed — kept for the trailing-time-
 * clause regex strip in the title-extraction path (the slot extractor
 * strips it from the title so "remind me at 5pm" doesn't end up with
 * title="at 5pm").
 *
 * The JVM impl uses prettytime-nlp's `Date` output and converts at the
 * boundary; commonMain consumers see only [Instant].
 */
data class ParsedTime(
    val instant: Instant,
    val matchedText: String,
)
