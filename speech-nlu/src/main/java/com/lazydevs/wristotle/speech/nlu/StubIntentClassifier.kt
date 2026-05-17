package com.lazydevs.wristotle.speech.nlu

/**
 * Default classifier used until a real model is downloaded + active.
 *
 * Always returns [Intent.Unknown] with zero confidence — this lets every
 * downstream consumer assume an [IntentResult] is available for any query,
 * while preserving today's behavior (the dispatcher falls back to its
 * existing prefix-matching path when intent is Unknown).
 */
class StubIntentClassifier : IntentClassifier {
    override val tag: String = "stub"

    override suspend fun classify(query: String): IntentResult = IntentResult(
        intent = Intent.Unknown,
        slots = emptyMap(),
        confidence = 0f,
        alternates = emptyList(),
        rawQuery = query,
    )
}
