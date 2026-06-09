// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

/**
 * A learned training example surfaced to the classifier at warm-up
 * time. Pure data class with no persistence story attached — the
 * classifier reads these via the `loadLearned` lambda it's
 * constructed with; how the consumer actually loads them
 * (Room-backed bank on Android, in-memory list on iOS / tests,
 * etc.) is out of scope here.
 *
 *  - [id] is opaque to the classifier — only surfaced so warm-up
 *    logs can identify which row failed to embed if one does.
 *  - [intent] is the [com.lazydevs.wristotle.speech.nlu.Intent]'s
 *    name (not ordinal) so the consumer's storage can grow new
 *    intents without coordinating an int mapping.
 *  - [rawText] is the user's verbatim phrasing — embedded as-is.
 */
data class LearnedExample(
    val id: Long,
    val intent: String,
    val rawText: String,
)
