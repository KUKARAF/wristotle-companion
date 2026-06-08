// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import kotlin.math.sqrt

/**
 * Cosine similarity on float vectors. We L2-normalize embeddings up-front
 * (see [MiniLmEmbedder]) so the runtime hot-path is just a dot product —
 * keep the divide-by-magnitudes variant available for unnormalized callers
 * but expect [dot] to be the one used at classify-time.
 */
internal object CosineSimilarity {

    /** Dot product. Assumes [a] and [b] are L2-normalized; returns cosine. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size mismatch: ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Returns L2-normalized copy of [v]. Returns the input untouched if it's already zero-length. */
    fun normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += (x * x).toDouble()
        val norm = sqrt(sumSq).toFloat()
        if (norm == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }
}