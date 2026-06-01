package com.lazydevs.wristotle.speech.nlu.embedding

/**
 * Test [Embedder] that returns predetermined vectors keyed by exact
 * text match. Lets [EmbeddingIntentClassifier]'s ranking logic be
 * exercised against synthetic embedding geometry without the 22 MB
 * MiniLM model on disk.
 *
 * Vectors should be L2-normalized — the classifier uses
 * [CosineSimilarity.dot] which assumes unit-length inputs. Use
 * [unit2d] to build them on the unit circle by angle.
 *
 * Throws on lookup miss rather than returning a zero vector so a test
 * that forgets to register an embedding fails loudly instead of
 * silently producing degenerate scores.
 */
internal class FakeEmbedder(
    private val embeddings: Map<String, FloatArray>,
) : Embedder {

    var embedCallCount: Int = 0
        private set

    override fun embed(text: String): FloatArray {
        embedCallCount++
        return embeddings[text]
            ?: error("FakeEmbedder has no entry for: '$text'. Register it in the map.")
    }

    override fun close() {
        /* nothing to release */
    }
}

/**
 * Unit vector on the 2D unit circle at the given angle in degrees.
 * Lets tests express "this query is near intent A" by picking an angle
 * close to A's seed cluster — cosine between two such vectors is just
 * `cos(angle_a - angle_b)`.
 */
internal fun unit2d(angleDeg: Double): FloatArray {
    val rad = angleDeg * Math.PI / 180.0
    return floatArrayOf(Math.cos(rad).toFloat(), Math.sin(rad).toFloat())
}
