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
            displayName = "MiniLM L6 v2 (INT8, ~23 MB)",
            approxSizeBytes = 23_000_000L,
            architectureLabel = "MiniLM-L6",
            url = "$HF_BASE/model_quint8_avx2.onnx",
        ),
    )

    fun byId(id: String): NluModelInfo? = all.firstOrNull { it.id == id }
}
