// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.nlu.learning

/**
 * In-memory [ExampleDao] for tests that mimics the production Room
 * behaviour around the unique `normalizedText` index. Not a Room code
 * generator — purpose-built so the rules under test stay legible.
 *
 * Shared between [ExampleBankTest] and
 * `EmbeddingIntentClassifierTest` so they exercise the same "what
 * does the bank look like" model.
 */
internal class FakeExampleDao : ExampleDao {
    val rows: MutableList<ExampleEntry> = mutableListOf()
    private var nextId: Long = 1

    override suspend fun learned(): List<ExampleEntry> =
        rows.filter { it.source == "learned" }

    override suspend fun learnedForIntent(intent: String): List<ExampleEntry> =
        rows.filter { it.source == "learned" && it.intent == intent }

    override suspend fun findByNormalized(norm: String): ExampleEntry? =
        rows.firstOrNull { it.normalizedText == norm }

    override suspend fun insert(entry: ExampleEntry): Long {
        // Mirror IGNORE-on-conflict: if normalizedText already exists, return 0.
        if (rows.any { it.normalizedText == entry.normalizedText }) return 0L
        val withId = entry.copy(id = nextId++)
        rows += withId
        return withId.id
    }

    override suspend fun update(entry: ExampleEntry) {
        val idx = rows.indexOfFirst { it.id == entry.id }
        if (idx >= 0) rows[idx] = entry
    }

    override suspend fun deleteLearned() {
        rows.removeAll { it.source == "learned" }
    }

    override suspend fun deleteLearnedById(id: Long): Int {
        val removed = rows.removeAll { it.id == id && it.source == "learned" }
        return if (removed) 1 else 0
    }

    override suspend fun countLearnedForIntent(intent: String): Int =
        rows.count { it.source == "learned" && it.intent == intent }

    override suspend fun countLearned(): Int =
        rows.count { it.source == "learned" }
}