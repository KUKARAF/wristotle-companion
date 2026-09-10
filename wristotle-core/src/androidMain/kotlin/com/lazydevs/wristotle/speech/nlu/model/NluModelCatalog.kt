// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.model

/**
 * Known NLU sentence-encoder models the user can download.
 *
 * V1 ships the INT8-quantized MiniLM-L6-v2 (~23 MB) as the only choice —
 * tuned for short imperative utterances which is exactly our domain. The
 * FP32 variant (~90 MB) can be added here later if accuracy regressions
 * surface; the runtime code is identical.
 */
object NluModelCatalog {

    private const val HF_BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx"

    val all: List<NluModelInfo> = listOf(
        NluModelInfo(
            id = "minilm-l6-v2-int8",
            displayName = "MiniLM L6 v2",
            approxSizeBytes = 23_000_000L,
            architectureLabel = "INT8 quantized",
            url = "$HF_BASE/model_quint8_avx2.onnx",
        ),
    )

    fun byId(id: String): NluModelInfo? = all.firstOrNull { it.id == id }

    /** The model the first-run wizard auto-downloads (the sole NLU model today). */
    fun recommendedDefault(): NluModelInfo = all.first()
}