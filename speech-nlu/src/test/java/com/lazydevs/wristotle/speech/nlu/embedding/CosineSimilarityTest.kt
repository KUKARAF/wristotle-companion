package com.lazydevs.wristotle.speech.nlu.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-math tests for the classifier's similarity primitive. No model,
 * no ONNX. If [CosineSimilarity.dot] ever drifts off correctness, every
 * top-K assertion in the eval harness silently lies — guard the floor.
 */
class CosineSimilarityTest {

    // ── dot ───────────────────────────────────────────────────────────────

    @Test fun `identical normalized vectors give cosine 1`() {
        val v = CosineSimilarity.normalize(floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(1f, CosineSimilarity.dot(v, v), TOL)
    }

    @Test fun `orthogonal normalized vectors give cosine 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, CosineSimilarity.dot(a, b), TOL)
    }

    @Test fun `opposite normalized vectors give cosine -1`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1f, CosineSimilarity.dot(a, b), TOL)
    }

    @Test fun `dot is commutative`() {
        val a = CosineSimilarity.normalize(floatArrayOf(0.3f, -0.5f, 0.8f, 1.1f))
        val b = CosineSimilarity.normalize(floatArrayOf(1.7f, 0.2f, -0.9f, 0.4f))
        assertEquals(CosineSimilarity.dot(a, b), CosineSimilarity.dot(b, a), TOL)
    }

    @Test fun `size mismatch throws`() {
        try {
            CosineSimilarity.dot(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("size mismatch"))
        }
    }

    // ── normalize ─────────────────────────────────────────────────────────

    @Test fun `normalize gives unit length`() {
        val v = CosineSimilarity.normalize(floatArrayOf(3f, 4f))
        val mag = sqrt((v[0] * v[0] + v[1] * v[1]).toDouble()).toFloat()
        assertEquals(1f, mag, TOL)
    }

    @Test fun `normalize of zero vector returns the same instance untouched`() {
        // Documented behaviour — a zero embedding (degenerate, shouldn't
        // happen but defended) is returned as-is rather than producing NaN.
        // Identity check matters: callers cache by reference in some paths.
        val zero = floatArrayOf(0f, 0f, 0f)
        assertSame(zero, CosineSimilarity.normalize(zero))
    }

    @Test fun `normalize does not mutate the input`() {
        val v = floatArrayOf(2f, 0f, 0f)
        CosineSimilarity.normalize(v)
        assertEquals(2f, v[0], 0f)
    }

    @Test fun `normalize then dot gives true cosine`() {
        // Two unnormalized vectors — manual cosine = a·b / (|a|*|b|).
        // Normalize-then-dot must match.
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 3f, 4f)
        val rawDot = 1 * 2 + 2 * 3 + 3 * 4f       // 20
        val magA = sqrt(1f + 4f + 9f)
        val magB = sqrt(4f + 9f + 16f)
        val expected = rawDot / (magA * magB)
        val actual = CosineSimilarity.dot(
            CosineSimilarity.normalize(a),
            CosineSimilarity.normalize(b),
        )
        assertEquals(expected, actual, TOL)
    }

    @Test fun `near-similar vectors give cosine close to 1`() {
        val a = CosineSimilarity.normalize(floatArrayOf(1f, 0f, 0f))
        val b = CosineSimilarity.normalize(floatArrayOf(0.99f, 0.01f, 0f))
        val score = CosineSimilarity.dot(a, b)
        assertTrue("expected >0.99, got $score", score > 0.99f)
        assertNotEquals(1f, score)
    }

    private companion object {
        // 1e-6 is comfortably within Float math precision for vectors of
        // length 1-1000 — the embeddings in production are length 384.
        const val TOL = 1e-6f
    }
}
