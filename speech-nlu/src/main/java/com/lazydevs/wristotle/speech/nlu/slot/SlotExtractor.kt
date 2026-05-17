package com.lazydevs.wristotle.speech.nlu.slot

import com.lazydevs.wristotle.speech.nlu.Intent

/**
 * Pulls structured parameters out of a free-form query for a specific intent.
 * Runs after intent classification, so the extractor knows which intent it's
 * extracting for (and can use intent-specific rules).
 *
 * Return an empty map when the extractor can't find its slots — the handler
 * may still succeed with a fallback, or report a useful error.
 *
 * Suspend because some extractors (e.g. SMS contact-resolution) may need to
 * query the contacts DB on IO.
 */
fun interface SlotExtractor {
    suspend fun extract(query: String): Map<String, Any>
}

/**
 * Registry that maps each [Intent] to its slot extractor. The consumer
 * module builds and installs this in its `Application.onCreate`.
 *
 * Phase 1 only declares the interface; real per-intent extractors land in
 * Phase 3 alongside the handler refactor.
 */
class SlotExtractorRegistry(private val byIntent: Map<Intent, SlotExtractor>) {
    suspend fun extract(intent: Intent, query: String): Map<String, Any> =
        byIntent[intent]?.extract(query) ?: emptyMap()
}
