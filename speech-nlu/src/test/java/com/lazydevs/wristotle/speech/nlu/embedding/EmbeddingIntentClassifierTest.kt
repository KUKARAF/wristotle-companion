// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.bank.ExampleBank
import com.lazydevs.wristotle.speech.nlu.bank.FakeExampleDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classifier-internal logic tests using synthetic 2D unit-vector
 * embeddings — no MiniLM model, no ONNX, no Whisper. Covers the bits
 * that the [com.lazydevs.wristotle.nlu.VoicePipeline] tests intentionally
 * delegate to a [FakeIntentClassifier]:
 *
 *  - Per-intent top-K-mean ranking (and the "noisy seed can't drag the
 *    intent down" invariant it exists to provide).
 *  - Alternates list shape + ordering.
 *  - Empty / blank degenerate inputs.
 *  - rebuild() picking up newly-learned rows.
 *  - classify() auto-warms on first call.
 *
 * What it does NOT cover: real-world embedding quality — i.e. does the
 * actual MiniLM model place "remind me to call mom" near the Reminder
 * cluster? That's the deferred NLU eval harness in TODO.md § Testing.
 */
class EmbeddingIntentClassifierTest {

    // ── Geometry helpers ──────────────────────────────────────────────────
    //
    // Each test sets up its embedding world by pinning an angle on the
    // unit circle to each intent's seed cluster. Query "near angle θ"
    // gives cosine ≈ cos(θ - seed_angle) for each seed — easy to reason
    // about manually.

    private fun bankWithLearned(): Pair<ExampleBank, FakeExampleDao> {
        val dao = FakeExampleDao()
        return ExampleBank(dao) to dao
    }

    private fun classifier(
        seeds: List<Pair<Intent, String>>,
        embeddings: Map<String, FloatArray>,
        bank: ExampleBank = bankWithLearned().first,
    ): EmbeddingIntentClassifier = EmbeddingIntentClassifier(
        embedder = FakeEmbedder(embeddings),
        bank = bank,
        seeds = seeds,
    )

    // ── Degenerate input ──────────────────────────────────────────────────

    @Test fun `blank query returns Unknown without warming up`() = runBlocking {
        val embedder = FakeEmbedder(emptyMap())
        val c = EmbeddingIntentClassifier(
            embedder = embedder,
            bank = bankWithLearned().first,
            seeds = emptyList(),
        )
        val r = c.classify("")
        assertEquals(Intent.Unknown, r.intent)
        assertEquals(0f, r.confidence)
        assertTrue(r.alternates.isEmpty())
        assertEquals("must not call embed for blank", 0, embedder.embedCallCount)
    }

    @Test fun `empty seed bank with no learned rows yields Unknown`() = runBlocking {
        val c = classifier(
            seeds = emptyList(),
            embeddings = mapOf("hello" to unit2d(0.0)),
        )
        val r = c.classify("hello")
        assertEquals(Intent.Unknown, r.intent)
        assertEquals(0f, r.confidence)
    }

    // ── Basic single-intent routing ──────────────────────────────────────

    @Test fun `single seed exact match returns that intent at near-1 confidence`() = runBlocking {
        val c = classifier(
            seeds = listOf(Intent.Reminder to "remind me at 5pm"),
            embeddings = mapOf(
                "remind me at 5pm" to unit2d(0.0),
                "remind me at 5pm please" to unit2d(0.0), // same angle = perfect alignment
            ),
        )
        val r = c.classify("remind me at 5pm please")
        assertEquals(Intent.Reminder, r.intent)
        assertEquals(1.0f, r.confidence, 1e-5f)
    }

    @Test fun `query nearer to one of two intents picks that intent`() = runBlocking {
        // Reminder cluster at 0°, Call cluster at 90°. Query at 10° is
        // much closer to Reminder.
        val c = classifier(
            seeds = listOf(
                Intent.Reminder to "remind me to X",
                Intent.Call to "call X",
            ),
            embeddings = mapOf(
                "remind me to X" to unit2d(0.0),
                "call X" to unit2d(90.0),
                "remind me about something" to unit2d(10.0),
            ),
        )
        val r = c.classify("remind me about something")
        assertEquals(Intent.Reminder, r.intent)
        // Confidence is cos(10°) ≈ 0.985, runner-up cos(80°) ≈ 0.174.
        assertTrue("expected confidence >0.95, got ${r.confidence}", r.confidence > 0.95f)
        assertEquals(1, r.alternates.size)
        assertEquals(Intent.Call, r.alternates[0].intent)
    }

    // ── Per-intent top-K averaging (the "noisy seed" invariant) ──────────

    @Test fun `noisy seeds in an intent do not drag its score below another intent`() = runBlocking {
        // Reminder has 3 tight seeds at angles 0°, 1°, -1° AND 2 anti-
        // correlated noisy seeds at 178° and -178°. Without top-K
        // averaging, the all-seeds mean would be ≈ 0.08 (noisy ones
        // anti-correlate the centroid) and Call's 0.675 would win —
        // wrong. With top-K=3, Reminder's score = average of its 3
        // closest matches ≈ 0.9999, which dominates.
        val c = classifier(
            seeds = listOf(
                Intent.Reminder to "r0",
                Intent.Reminder to "r1",
                Intent.Reminder to "r2",
                Intent.Reminder to "r_noisy_a",
                Intent.Reminder to "r_noisy_b",
                Intent.Call to "c0",
                Intent.Call to "c1",
            ),
            embeddings = mapOf(
                "r0" to unit2d(0.0),
                "r1" to unit2d(1.0),
                "r2" to unit2d(-1.0),
                "r_noisy_a" to unit2d(178.0),  // nearly opposite
                "r_noisy_b" to unit2d(-178.0),
                "c0" to unit2d(45.0),
                "c1" to unit2d(50.0),
                "QUERY" to unit2d(0.0),
            ),
        )
        val r = c.classify("QUERY")
        assertEquals(Intent.Reminder, r.intent)
        assertTrue("Reminder score should be near 1.0, got ${r.confidence}", r.confidence > 0.999f)
    }

    @Test fun `intent with fewer than K seeds averages what it has`() = runBlocking {
        // Call has only 1 seed → its "top-3 mean" is just that seed.
        // Documented behaviour — no error, no zero-fill.
        val c = classifier(
            seeds = listOf(
                Intent.Call to "call",
                Intent.Reminder to "r0",
                Intent.Reminder to "r1",
                Intent.Reminder to "r2",
            ),
            embeddings = mapOf(
                "call" to unit2d(0.0),
                "r0" to unit2d(90.0),
                "r1" to unit2d(91.0),
                "r2" to unit2d(89.0),
                "QUERY" to unit2d(0.0),
            ),
        )
        val r = c.classify("QUERY")
        assertEquals(Intent.Call, r.intent)
        assertEquals(1.0f, r.confidence, 1e-5f)
    }

    // ── Alternates list shape ────────────────────────────────────────────

    @Test fun `alternates are ranked highest-first and skip the winner`() = runBlocking {
        // Five intents on the unit circle, all distinct cosines vs the
        // query at 0°. Expected descending order:
        //   Reminder  0°   cos=1.0
        //   Note     30°   cos≈0.866
        //   Call     60°   cos=0.5
        //   Weather 120°   cos=-0.5
        //   Cancel  150°   cos≈-0.866
        val c = classifier(
            seeds = listOf(
                Intent.Reminder to "remind",
                Intent.Note to "note",
                Intent.Call to "call",
                Intent.Weather to "weather",
                Intent.Cancel to "cancel",
            ),
            embeddings = mapOf(
                "remind" to unit2d(0.0),
                "note" to unit2d(30.0),
                "call" to unit2d(60.0),
                "weather" to unit2d(120.0),
                "cancel" to unit2d(150.0),
                "QUERY" to unit2d(0.0),
            ),
        )
        val r = c.classify("QUERY")
        assertEquals(Intent.Reminder, r.intent)
        // K_ALTERNATES = 3 → the 4th & 5th-ranked intents do not appear.
        assertEquals(3, r.alternates.size)
        assertEquals(Intent.Note, r.alternates[0].intent)
        assertEquals(Intent.Call, r.alternates[1].intent)
        assertEquals(Intent.Weather, r.alternates[2].intent)
        // Scores are monotonically non-increasing.
        for (i in 0 until r.alternates.size - 1) {
            assertTrue(
                "alternate ${i + 1} should not outrank alternate $i",
                r.alternates[i].score >= r.alternates[i + 1].score,
            )
        }
        // And the winner outranks the first alternate.
        assertTrue(r.confidence > r.alternates[0].score)
    }

    // ── Learned rows + rebuild ───────────────────────────────────────────

    @Test fun `rebuild picks up rows added to the bank after warm-up`() = runBlocking {
        val (bank, _) = bankWithLearned()
        val c = classifier(
            seeds = listOf(Intent.Reminder to "r0"),
            embeddings = mapOf(
                "r0" to unit2d(0.0),
                // Learned phrase will be added in Call territory.
                "call mom" to unit2d(90.0),
                "QUERY" to unit2d(89.0),
            ),
            bank = bank,
        )
        c.warmUp()

        // Before adding the learned row, no Call seed exists at all —
        // the only intent in scores is Reminder, so the query (at 89°,
        // near where Call WILL live) still routes to Reminder.
        assertEquals(Intent.Reminder, c.classify("QUERY").intent)

        // Insert a learned Call row and rebuild.
        assertTrue(bank.addLearned("call mom", Intent.Call))
        c.rebuild()

        // Now Call is in the bank — and the query, sitting at 89°,
        // routes to Call (cos(1°) ≈ 1) instead of Reminder (cos(89°) ≈ 0).
        val r = c.classify("QUERY")
        assertEquals(Intent.Call, r.intent)
        assertTrue("expected high confidence after learning, got ${r.confidence}", r.confidence > 0.99f)
    }

    @Test fun `classify auto-warms on first call`() = runBlocking {
        val embedder = FakeEmbedder(mapOf(
            "remind" to unit2d(0.0),
            "remind me about X" to unit2d(2.0),
        ))
        val c = EmbeddingIntentClassifier(
            embedder = embedder,
            bank = bankWithLearned().first,
            seeds = listOf(Intent.Reminder to "remind"),
        )
        // No explicit warmUp() — classify must self-warm.
        val r = c.classify("remind me about X")
        assertEquals(Intent.Reminder, r.intent)
        // Embedder saw: 1 seed (during the auto-warm) + 1 query embed.
        assertEquals(2, embedder.embedCallCount)
    }

    @Test fun `warmUp is idempotent`() = runBlocking {
        val embedder = FakeEmbedder(mapOf("remind" to unit2d(0.0)))
        val c = EmbeddingIntentClassifier(
            embedder = embedder,
            bank = bankWithLearned().first,
            seeds = listOf(Intent.Reminder to "remind"),
        )
        c.warmUp()
        c.warmUp()
        c.warmUp()
        // Embedder ran exactly once per seed; the second + third warm-up
        // are no-ops.
        assertEquals(1, embedder.embedCallCount)
    }

    // ── Metadata + isStub contract ───────────────────────────────────────

    @Test fun `tag identifies the classifier and isStub is false`() {
        val c = EmbeddingIntentClassifier(
            embedder = FakeEmbedder(emptyMap()),
            bank = bankWithLearned().first,
            seeds = emptyList(),
        )
        assertEquals("embedding", c.tag)
        // Inverse of StubIntentClassifier — VoicePipeline reads this
        // to decide whether to surface the "no NLU model" hint.
        assertFalse(c.isStub)
    }

    // ── close() releases the embedder ────────────────────────────────────

    @Test fun `close delegates to embedder close`() {
        val tracking = object : Embedder {
            var closed: Boolean = false
            override fun embed(text: String): FloatArray = error("not used")
            override fun close() { closed = true }
        }
        val c = EmbeddingIntentClassifier(
            embedder = tracking,
            bank = bankWithLearned().first,
            seeds = emptyList(),
        )
        c.close()
        assertTrue(tracking.closed)
    }

    // ── Negative confidence (query opposite to every seed) ───────────────

    @Test fun `query opposite to all seeds returns the top intent with negative confidence`() = runBlocking {
        // Two intents, both with seeds near 0°, query at 180°. Top
        // intent picks deterministically (whichever has more seeds in
        // its top-K mean closer to the query — here both at -1.0) but
        // confidence is negative. Caller (VoicePipeline + watchHint)
        // would treat this as Unknown via ROUTE_THRESHOLD.
        val c = classifier(
            seeds = listOf(
                Intent.Reminder to "r0",
                Intent.Call to "c0",
            ),
            embeddings = mapOf(
                "r0" to unit2d(0.0),
                "c0" to unit2d(1.0),
                "QUERY" to unit2d(180.0),
            ),
        )
        val r = c.classify("QUERY")
        // Top intent picked, but confidence is ≈ -1 — the consumer's
        // ROUTE_THRESHOLD (0.55) gates it out. Document the shape so
        // a future "if (confidence < 0) return Unknown" change is a
        // deliberate decision, not a silent contract drift.
        assertNotEquals(Intent.Unknown, r.intent)
        assertTrue("expected negative confidence, got ${r.confidence}", r.confidence < 0f)
    }
}