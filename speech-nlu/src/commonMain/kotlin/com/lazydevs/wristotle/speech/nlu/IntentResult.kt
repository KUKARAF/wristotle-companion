// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu

/** One of the top-K alternates returned alongside the chosen intent. */
data class RankedIntent(val intent: Intent, val score: Float)

/**
 * Output of [IntentClassifier.classify].
 *
 * @property intent       The chosen intent. May be [Intent.Unknown] if no
 *                        match cleared the confidence threshold.
 * @property slots        Structured parameters for the matched intent
 *                        (e.g. `{"contact": "Mom"}` for [Intent.Call]).
 *                        Empty map when no slot extractor ran.
 * @property confidence   Raw cosine similarity (0..1) for embedding-based
 *                        classifiers; semantics differ per implementation.
 * @property alternates   Top-K runners-up. Lets the router show "did you
 *                        mean…" UX or log ambiguity for tuning.
 * @property rawQuery     The original text. Handlers need it for fallback
 *                        paths; learning needs it to add to the bank.
 */
data class IntentResult(
    val intent: Intent,
    val slots: Map<String, Any>,
    val confidence: Float,
    val alternates: List<RankedIntent>,
    val rawQuery: String,
)