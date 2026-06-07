// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.bank

import android.util.Log
import com.lazydevs.wristotle.speech.nlu.Intent

private const val TAG = "ExampleBank"

/**
 * Repository over [ExampleDao] that the classifier reads at warm-up time
 * (seeds + already-learned rows) and that [LearningCollector] writes to
 * after successful dispatches.
 *
 * Normalization is centralized here so insert dedup and classifier-side
 * lookup agree on the canonical form.
 */
class ExampleBank(private val dao: ExampleDao) {

    suspend fun learnedExamples(): List<ExampleEntry> = dao.learned()

    /**
     * Add a learned example. Returns true if a new row was inserted (caller
     * may want to trigger a centroid rebuild); false if it was a duplicate
     * that just bumped the usage counter.
     */
    suspend fun addLearned(rawText: String, intent: Intent): Boolean {
        if (rawText.isBlank() || rawText.length > MAX_LEARNED_LEN) return false
        val normalized = normalize(rawText)
        val existing = dao.findByNormalized(normalized)
        if (existing != null) {
            dao.update(existing.copy(usageCount = existing.usageCount + 1))
            return false
        }
        if (dao.countLearnedForIntent(intent.name) >= MAX_LEARNED_PER_INTENT) {
            // Cap reached — silently skip. Future improvement: evict oldest
            // with lowest usage count. V1 keeps this trivially bounded.
            Log.d(TAG, "skip insert: per-intent cap reached for ${intent.name}")
            return false
        }
        val id = dao.insert(ExampleEntry(
            intent = intent.name,
            rawText = rawText,
            normalizedText = normalized,
            source = "learned",
            addedAtEpochMs = System.currentTimeMillis(),
        ))
        return id > 0L
    }

    suspend fun deleteAllLearned() = dao.deleteLearned()

    /** Delete a single learned row by id. Returns true iff a row was removed
     *  (caller may want to rebuild centroids). */
    suspend fun deleteLearnedById(id: Long): Boolean = dao.deleteLearnedById(id) > 0

    companion object {
        const val MAX_LEARNED_LEN = 120
        const val MAX_LEARNED_PER_INTENT = 200

        /** Lowercase + collapse whitespace + strip end-of-sentence punctuation. */
        fun normalize(text: String): String =
            text.lowercase().trim()
                .replace(Regex("\\s+"), " ")
                .trimEnd('.', ',', '!', '?', ';', ':')
    }
}