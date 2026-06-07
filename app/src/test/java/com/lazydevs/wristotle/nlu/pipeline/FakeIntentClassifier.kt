// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.pipeline

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.RankedIntent

/**
 * Test classifier that lets each row drive the pipeline's refiner without
 * needing the ONNX embedding model on disk. [pick] returns the classifier's
 * pretended verdict per query — return `null` to model "no match", which
 * yields an `Unknown @ 0.0` result and exercises the pipeline's
 * watch-hint / [PrefixHints] fallback paths.
 *
 * Set [isStub] = true to exercise the "no NLU model loaded" branch in
 * `PebbleListenerService` (only relevant once handler-dispatch tests land).
 */
class FakeIntentClassifier(
    override val isStub: Boolean = false,
    private val pick: (String) -> IntentResult? = { null },
) : IntentClassifier {
    override val tag: String = "fake"

    override suspend fun classify(query: String): IntentResult =
        pick(query) ?: IntentResult(
            intent = Intent.Unknown,
            slots = emptyMap(),
            confidence = 0f,
            alternates = emptyList(),
            rawQuery = query,
        )
}

/**
 * Convenience: build an [IntentResult] for the classifier to return.
 *
 * Pass [runnerUp] to model the "tight margin" path in
 * [com.lazydevs.wristotle.nlu.WatchHintRefiner.refineUnhinted] — the
 * runner-up sits in `alternates[0]` and triggers a PrefixHints rescue
 * when `confidence - runnerUp < ROUTE_MARGIN` (0.10).
 */
fun classified(
    intent: Intent,
    confidence: Float,
    query: String = "",
    runnerUp: Pair<Intent, Float>? = null,
): IntentResult = IntentResult(
    intent = intent,
    slots = emptyMap(),
    confidence = confidence,
    alternates = runnerUp?.let { listOf(RankedIntent(it.first, it.second)) } ?: emptyList(),
    rawQuery = query,
)