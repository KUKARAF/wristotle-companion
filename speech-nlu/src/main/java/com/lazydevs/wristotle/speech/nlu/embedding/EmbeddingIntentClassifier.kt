// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import android.util.Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentResult
import com.lazydevs.wristotle.speech.nlu.RankedIntent
import com.lazydevs.wristotle.speech.nlu.bank.ExampleBank
import com.lazydevs.wristotle.speech.nlu.bank.ExampleEntry
import com.lazydevs.wristotle.speech.nlu.seed.SeedExamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

private const val TAG = "EmbeddingIntentClassifier"

/**
 * Cosine-similarity intent classifier over a bank of pre-embedded examples.
 *
 * Pipeline per query:
 *   1. Tokenize → ONNX MiniLM → mean-pooled, L2-normalized 384-dim vector.
 *   2. Cosine vs every example's stored embedding; group by intent.
 *   3. Take the **mean of the top-K matches per intent** as that intent's
 *      score (centroid-of-relevant rather than centroid-of-all, so a noisy
 *      example doesn't drag a whole intent's score down).
 *   4. Return the winning intent + ranked alternates + raw cosine confidence.
 *
 * Seeds are embedded once at startup; learned rows are embedded on insert
 * via [rebuild] (called from [LearningCollector]). All embedding state is
 * guarded by a mutex so [rebuild] and [classify] never race.
 *
 * No slot extraction here — slots are populated by the caller's
 * [com.lazydevs.wristotle.speech.nlu.slot.SlotExtractorRegistry] in Phase 3.
 * For Phase 2 (shadow mode) [classify] returns `slots = emptyMap()`.
 */
class EmbeddingIntentClassifier(
    private val embedder: Embedder,
    private val bank: ExampleBank,
    private val seeds: List<Pair<Intent, String>> = SeedExamples.all,
) : IntentClassifier, Closeable {

    override val tag: String = "embedding"

    /** Per-example embedding cache. Each row: (intent, embedding). */
    private data class Embedded(val intent: Intent, val embedding: FloatArray)

    private val mutex = Mutex()
    private var examples: List<Embedded> = emptyList()

    /** Releases the underlying ONNX session. Call when evicting this classifier
     *  (e.g. on NLU model switch) so the native session memory (~80–100 MB) is
     *  freed promptly instead of lingering until GC. */
    override fun close() = embedder.close()

    /** Warm-up: embed seeds + any already-learned rows. Idempotent. */
    suspend fun warmUp() {
        mutex.withLock {
            if (examples.isNotEmpty()) return@withLock
            examples = withContext(Dispatchers.Default) {
                val seedEmbeds = seeds.map { (intent, text) ->
                    Embedded(intent, embedder.embed(text))
                }
                val learnedEmbeds = bank.learnedExamples().mapNotNull { entry ->
                    runCatching {
                        Embedded(Intent.fromName(entry.intent), embedder.embed(entry.rawText))
                    }.onFailure { Log.w(TAG, "skip learned entry ${entry.id}: ${it.message}") }
                        .getOrNull()
                }
                seedEmbeds + learnedEmbeds
            }
            Log.d(TAG, "warmUp: ${examples.size} embedded (${seeds.size} seed)")
        }
    }

    override suspend fun rebuild() {
        mutex.withLock {
            // Re-embed the learned rows; seed embeddings stay cached.
            val cachedSeeds = examples.take(seeds.size)
            val learned = withContext(Dispatchers.Default) {
                bank.learnedExamples().mapNotNull { entry ->
                    runCatching {
                        Embedded(Intent.fromName(entry.intent), embedder.embed(entry.rawText))
                    }.getOrNull()
                }
            }
            examples = cachedSeeds + learned
            Log.d(TAG, "rebuild: ${examples.size} embedded (${learned.size} learned)")
        }
    }

    override suspend fun classify(query: String): IntentResult {
        if (query.isBlank()) return unknown(query)
        if (examples.isEmpty()) warmUp()

        val queryEmb = withContext(Dispatchers.Default) { embedder.embed(query) }

        // Per-intent top-K mean. K=3 trades stability vs over-smoothing.
        val byIntent = examples.groupBy { it.intent }
        val scores = byIntent.mapValues { (_, list) ->
            val sims = list.map { CosineSimilarity.dot(queryEmb, it.embedding) }
            sims.sortedDescending().take(K_PER_INTENT).average().toFloat()
        }.toList().sortedByDescending { it.second }

        if (scores.isEmpty()) return unknown(query)

        val (topIntent, topScore) = scores.first()
        val alternates = scores.drop(1).take(K_ALTERNATES).map { (i, s) -> RankedIntent(i, s) }
        return IntentResult(
            intent = topIntent,
            slots = emptyMap(),    // Phase 3 populates via SlotExtractorRegistry
            confidence = topScore,
            alternates = alternates,
            rawQuery = query,
        )
    }

    private fun unknown(query: String) = IntentResult(
        intent = Intent.Unknown,
        slots = emptyMap(),
        confidence = 0f,
        alternates = emptyList(),
        rawQuery = query,
    )

    companion object {
        private const val K_PER_INTENT = 3
        private const val K_ALTERNATES = 3
    }
}