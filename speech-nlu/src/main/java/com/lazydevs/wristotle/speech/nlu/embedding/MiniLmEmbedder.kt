// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.LongBuffer

private const val TAG = "MiniLmEmbedder"

/**
 * Wraps an ONNX [OrtSession] for sentence-transformers all-MiniLM-L6-v2.
 *
 * Model output shape: `[1, seq, hidden=384]`. We mean-pool over the seq
 * dimension using the attention mask (so padding tokens don't dilute the
 * sentence vector), then L2-normalize so downstream callers can use plain
 * dot products as cosine similarity.
 *
 * Construction is heavy (~200–600 ms cold-start) so the embedder should be
 * cached at Application scope. [close] releases the native session.
 */
class MiniLmEmbedder(
    modelPath: String,
    private val tokenizer: Tokenizer,
) : Embedder {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = run {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env.createSession(modelPath, opts)
    }

    /** Tokenize, run the MiniLM session, mean-pool, L2-normalize. */
    override fun embed(text: String): FloatArray {
        val encoded = tokenizer.encode(text)
        val inputShape = longArrayOf(1L, encoded.length.toLong())

        val inputIdsBuf = LongBuffer.wrap(encoded.inputIds)
        val attentionMaskBuf = LongBuffer.wrap(encoded.attentionMask)
        // MiniLM expects token_type_ids too (all zeros for single-sentence input).
        val tokenTypeIdsBuf = LongBuffer.wrap(LongArray(encoded.length))

        val inputs = mutableMapOf<String, OnnxTensor>()
        inputs[INPUT_IDS] = OnnxTensor.createTensor(env, inputIdsBuf, inputShape)
        inputs[ATTENTION_MASK] = OnnxTensor.createTensor(env, attentionMaskBuf, inputShape)
        inputs[TOKEN_TYPE_IDS] = OnnxTensor.createTensor(env, tokenTypeIdsBuf, inputShape)

        try {
            session.run(inputs).use { results ->
                @Suppress("UNCHECKED_CAST")
                val raw = results[0].value as Array<Array<FloatArray>>
                val tokenEmbeddings = raw[0]   // shape [seq, hidden]
                val pooled = meanPool(tokenEmbeddings, encoded.attentionMask)
                return CosineSimilarity.normalize(pooled)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "embed failed for text=$text", t)
            throw t
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Mean-pool over the sequence dimension, ignoring padded positions. */
    private fun meanPool(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val hidden = tokenEmbeddings[0].size
        val out = FloatArray(hidden)
        var validCount = 0
        for (i in tokenEmbeddings.indices) {
            if (attentionMask[i] == 0L) continue
            validCount++
            val tok = tokenEmbeddings[i]
            for (h in 0 until hidden) out[h] += tok[h]
        }
        if (validCount > 0) {
            val inv = 1f / validCount
            for (h in 0 until hidden) out[h] *= inv
        }
        return out
    }

    override fun close() {
        session.close()
        // env is process-wide; don't close it.
    }

    companion object {
        // Standard MiniLM-L6-v2 ONNX input names.
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"

        const val EMBEDDING_DIM = 384
    }
}