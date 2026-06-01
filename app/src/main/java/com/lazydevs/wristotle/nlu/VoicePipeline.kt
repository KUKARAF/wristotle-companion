package com.lazydevs.wristotle.nlu

import com.lazydevs.wristotle.logging.WristotleLog as Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry

/**
 * Voice routing pipeline: query → classifier → [WatchHintRefiner] →
 * slot extraction → [IntentResult].
 *
 * Lifted out of `PebbleListenerService.resolveIntent` so the routing
 * layer has a single owner that's exercisable without dragging the
 * PebbleKit2 / Android Service plumbing into a fixture. Mirrors the
 * v0.15.5 [WatchHintRefiner] extraction.
 *
 * Production wiring lives in `WristotleApplication`. Tests build their
 * own [VoicePipeline] over a fake [IntentClassifier] + a real
 * [SlotExtractorRegistry] with lambda stubs for the few slots that
 * need contacts / settings. See `tests.md`.
 */
class VoicePipeline(
    private val classifier: IntentClassifier,
    private val slotExtractors: SlotExtractorRegistry,
    private val askAgentSubjects: () -> List<String> = { emptyList() },
    private val routeThreshold: Float = NluSettings.ROUTE_THRESHOLD,
    private val routeMargin: Float = NluSettings.ROUTE_MARGIN,
) {

    /** Exposed so callers can detect "no NLU model loaded" without poking
     *  the classifier directly. */
    val isStubClassifier: Boolean get() = classifier.isStub

    /**
     * Classify → refine → extract. Classifier failure is swallowed (a
     * crashing classifier shouldn't break the whole voice path) — the
     * refiner sees a null classified result and falls back to the watch
     * hint and [PrefixHints].
     *
     * Returns both the [Routed.result] (post-refinement, what the
     * handler dispatches on) and the [Routed.classified] (raw classifier
     * pick, kept around for log lines that want to record what the
     * model actually said before refinement overrode it).
     */
    suspend fun route(query: String, watchHint: Intent? = null): Routed {
        val classified = runCatching { classifier.classify(query) }
            .onFailure { Log.w(TAG, "classify failed", it) }
            .getOrNull()
        val refined = WatchHintRefiner.refine(
            classified = classified,
            watchHint = watchHint,
            query = query,
            routeThreshold = routeThreshold,
            routeMargin = routeMargin,
            customAskAgentSubjects = askAgentSubjects(),
        )
        val intent = refined ?: Intent.Unknown
        val slots = if (intent == Intent.Unknown) emptyMap() else slotExtractors.extract(intent, query)
        val result = classified?.copy(intent = intent, slots = slots)
            ?: IntentResult(
                intent = intent,
                slots = slots,
                confidence = if (refined != null) 1f else 0f,
                alternates = emptyList(),
                rawQuery = query,
            )
        return Routed(result = result, classified = classified)
    }

    /** Pipeline output. [result] is the final action to dispatch on;
     *  [classified] is the raw classifier pick (null on classifier failure)
     *  — kept so log lines can record what the model said before refinement. */
    data class Routed(
        val result: IntentResult,
        val classified: IntentResult?,
    )

    private companion object {
        const val TAG = "VoicePipeline"
    }
}
