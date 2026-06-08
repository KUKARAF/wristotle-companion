// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import java.io.Closeable

/**
 * Sentence-embedding contract that [EmbeddingIntentClassifier] reads
 * through. Production wires [MiniLmEmbedder] (ONNX MiniLM-L6-v2);
 * tests wire a synthetic implementation that returns predetermined
 * vectors per query so the classifier's ranking logic can be exercised
 * without the 22 MB model on disk.
 *
 * Implementations own their tokenizer — keeps callers from threading a
 * tokenizer through every embed call and lets the test impl ignore
 * tokenization entirely.
 */
interface Embedder : Closeable {

    /**
     * Tokenize, run the model, mean-pool, L2-normalize. The returned
     * vector is unit length so downstream callers can use a plain dot
     * product as cosine similarity.
     */
    fun embed(text: String): FloatArray
}