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

    /** Classify a raw user query into intent + slots + confidence. */
    suspend fun classify(query: String): IntentResult

    /**
     * Hook for implementations that maintain state (e.g. an updated example
     * bank after implicit learning). No-op for stateless classifiers.
     */
    suspend fun rebuild() {}
}
