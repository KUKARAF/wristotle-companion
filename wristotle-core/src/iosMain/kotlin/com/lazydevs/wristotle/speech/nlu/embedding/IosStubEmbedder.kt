// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS-side **stub** embedder for the spike.
 *
 * Returns a deterministic unit-length vector derived from `text.hashCode()`
 * so the [EmbeddingIntentClassifier] has something to dot-product against
 * during S3's "Swift CLI proves the brain runs on iOS" demo. The classifier
 * will return *some* intent for *some* query — the goal is to validate
 * end-to-end wiring (Swift → kotlin/native framework → classifier →
 * IntentResult), not to produce correct NLU output.
 *
 * S2 replaces this with a real ONNX Runtime iOS binding running the actual
 * MiniLM-L6-v2 model. The shape of [embed] (FloatArray of length
 * [VECTOR_DIM], L2-normalized) is preserved across the swap so nothing
 * downstream needs to change.
 *
 * Vector recipe: pick two unit-circle points (cos / sin of hash-derived
 * angle), then pad the rest with zeros. Synthetic but deterministic — the
 * same query always gets the same vector, so the classifier's caching +
 * warm-up paths exercise correctly.
 */
class IosStubEmbedder : Embedder {

    override fun embed(text: String): FloatArray {
        val angle = (text.hashCode() % 360).toDouble()
        return FloatArray(VECTOR_DIM).also {
            it[0] = cos(angle).toFloat()
            it[1] = sin(angle).toFloat()
            // Remaining entries left at 0.0f. The first two coords carry
            // a unit-length component; rest are padding so the shape
            // matches MiniLM-L6-v2's 384-dim output.
        }
    }

    override fun close() {
        // No native resources to release — the real iOS embedder will
        // free the ONNX session here.
    }

    companion object {
        /** Matches MiniLM-L6-v2's output dim so a stub vector slots
         *  into the same downstream cosine math without surprises. */
        const val VECTOR_DIM: Int = 384
    }
}
