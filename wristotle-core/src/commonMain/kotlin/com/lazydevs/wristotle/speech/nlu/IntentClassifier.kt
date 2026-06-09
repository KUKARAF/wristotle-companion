// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu

/**
 * Pluggable on-device NLU. Implementations:
 *  - [StubIntentClassifier]            always returns [Intent.Unknown]
 *  - `EmbeddingIntentClassifier`       ONNX MiniLM + cosine to example bank (Phase 2)
 *  - future: `LlmIntentClassifier`     small on-device LLM
 *  - future: `CloudIntentClassifier`   network API, opt-in
 *
 * Implementations must be safe to call from any dispatcher and route to the
 * right pool internally — typically [kotlinx.coroutines.Dispatchers.Default]
 * for CPU-bound inference or `IO` for network/cloud variants.
 *
 * Never throws — on internal failure return `IntentResult(Unknown, …, 0f, …)`
 * so the dispatch path can fall back cleanly.
 */
interface IntentClassifier {
    /** Short identifier for logging and conversation-history tagging. */
    val tag: String

    /**
     * True for the always-Unknown stub used when no NLU model is loaded.
     * Consumers branch on this to surface a "download an NLU model" hint
     * instead of the misleading "Unknown command: …" from the registry.
     * Typed property rather than `tag == "stub"` so a rename in the stub
     * implementation is a compile error, not a silently-lost hint.
     */
    val isStub: Boolean
        get() = false

    /** Classify a raw user query into intent + slots + confidence. */
    suspend fun classify(query: String): IntentResult

    /**
     * Hook for implementations that maintain state (e.g. an updated example
     * bank after implicit learning). No-op for stateless classifiers.
     */
    suspend fun rebuild() {}
}