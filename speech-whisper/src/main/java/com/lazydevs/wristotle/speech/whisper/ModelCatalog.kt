// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.whisper

/**
 * The speed/accuracy axis users actually choose along. Each tier maps to one
 * *recommended* model; raw variant names + quantization are an implementation
 * detail surfaced only under "All models".
 */
enum class ModelTier(val label: String, val blurb: String) {
    FAST("Fast", "Fastest — good for short commands"),
    BALANCED("Balanced", "Recommended for most people"),
    ACCURATE("Accurate", "Most accurate, but large and slow"),
}

/**
 * Metadata describing a downloadable Whisper model.
 *
 * Sizes are approximate (rounded to the nearest MB advertised by the upstream
 * HuggingFace mirror) and used only for the UI — the real file size after
 * download may differ slightly.
 */
data class ModelInfo(
    /** Stable id used as the filename suffix (`tiny.en` → `ggml-tiny.en.bin`). */
    val id: String,
    /** User-facing display name shown in the model picker. */
    val displayName: String,
    /** Approximate download size in bytes. */
    val approxSizeBytes: Long,
    /** "English" or "Multilingual" — purely a label for the UI. */
    val languageLabel: String,
    /** Resolvable HTTPS URL to the `.bin` file on the upstream mirror. */
    val url: String,
    /** Speed/accuracy bucket this model represents in the picker. */
    val tier: ModelTier,
    /**
     * Shown in the default (curated) picker. Exploratory / power-user variants
     * leave this `false` so they appear ONLY under "All models" — adding a new
     * model to explore never grows the default list.
     */
    val recommended: Boolean = false,
    // TODO: SHA-256 for integrity verification — skipped in v1; downloads
    //       only trust HuggingFace's HTTPS transport for now.
)

/** Known Whisper models the user can download from this app. */
object ModelCatalog {

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    /** The model the first-run wizard auto-downloads for one-tap setup: the
     *  BALANCED recommended pick — a sensible speed/accuracy trade-off on
     *  modest phones. Users can switch tiers later under Settings → Models. */
    fun recommendedDefault(): ModelInfo = all.first { it.tier == ModelTier.BALANCED && it.recommended }

    val all: List<ModelInfo> = listOf(
        // ── Recommended: one per tier, the curated default picker. ──
        // Quantized variants — ~50–60% the disk + memory footprint of the
        // full models with negligible accuracy loss on short commands.
        ModelInfo(
            id = "tiny.en-q5_1",
            displayName = "Tiny (English)",
            approxSizeBytes = 32_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-tiny.en-q5_1.bin",
            tier = ModelTier.FAST,
            recommended = true,
        ),
        ModelInfo(
            id = "base.en-q5_1",
            displayName = "Base (English)",
            approxSizeBytes = 60_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-base.en-q5_1.bin",
            tier = ModelTier.BALANCED,
            recommended = true,
        ),
        // Recommended ACCURATE pick is the q5_1 small variant (~190 MB) —
        // the full small.en at 466 MB is too RAM-hostile for most
        // non-flagship phones to load without paging, so it stays
        // available under "All models" but is not the default.
        ModelInfo(
            id = "small.en-q5_1",
            displayName = "Small (English)",
            approxSizeBytes = 190_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-small.en-q5_1.bin",
            tier = ModelTier.ACCURATE,
            recommended = true,
        ),

        // ── q4_0 quantization — fastest format on memory-bandwidth-bound
        //    CPUs (i.e. the ~2019-era ARM hardware that's our perf
        //    target) per whisper.cpp's own benchmarks. Quality "identical
        //    to higher bitrates" for ASR. Smaller download than q5_1. ──
        ModelInfo(
            id = "tiny.en-q4_0",
            displayName = "Tiny (English, q4_0)",
            approxSizeBytes = 25_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-tiny.en-q4_0.bin",
            tier = ModelTier.FAST,
        ),
        ModelInfo(
            id = "base.en-q4_0",
            displayName = "Base (English, q4_0)",
            approxSizeBytes = 50_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-base.en-q4_0.bin",
            tier = ModelTier.BALANCED,
        ),
        ModelInfo(
            id = "small.en-q4_0",
            displayName = "Small (English, q4_0)",
            approxSizeBytes = 145_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-small.en-q4_0.bin",
            tier = ModelTier.ACCURATE,
        ),

        // ── Full unquantised small.en — the ACCURATE tier's heaviest
        //    catalogued option, kept for users who want max accuracy on
        //    a 4+ GB phone. Demoted from `recommended` in v0.15.6: too
        //    RAM-hostile for typical 2-3 GB hardware. ──
        ModelInfo(
            id = "small.en",
            displayName = "Small (English, full)",
            approxSizeBytes = 466_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-small.en.bin",
            tier = ModelTier.ACCURATE,
        ),
        // Note: medium.en and large-v3-turbo were briefly catalogued in
        // v0.15.6 but removed in v0.15.7. Inference on phone CPU took
        // ~15-20 s for a 3-5 s clip even on Pixel 10a-class hardware,
        // exceeding the watch's session timeout. The defence-in-depth
        // checks in WhisperRecognizer (skip warm-up for n_audio_state ≥
        // 1024) and wristotle_speech.cpp (disable audio_ctx truncation
        // for n_audio_state > 768) still stand for any future catalog
        // entry that lands above the small encoder size.

        // ── Advanced / power-user: full (non-quantized) + multilingual
        //    variants. Hidden behind "All models"; adding more here never
        //    grows the default picker. ──
        ModelInfo(
            id = "tiny.en",
            displayName = "Tiny (English, full)",
            approxSizeBytes = 77_700_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-tiny.en.bin",
            tier = ModelTier.FAST,
        ),
        ModelInfo(
            id = "base.en",
            displayName = "Base (English, full)",
            approxSizeBytes = 142_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-base.en.bin",
            tier = ModelTier.BALANCED,
        ),
        ModelInfo(
            id = "base",
            displayName = "Base (Multilingual)",
            approxSizeBytes = 142_000_000L,
            languageLabel = "Multilingual",
            url = "$HF_BASE/ggml-base.bin",
            tier = ModelTier.BALANCED,
        ),
    )

    /** Curated default picker — the `recommended` models (one per tier). */
    val recommended: List<ModelInfo> = all.filter { it.recommended }

    fun byId(id: String): ModelInfo? = all.firstOrNull { it.id == id }

    /** Models in a given tier (used to group the "All models" view). */
    fun byTier(tier: ModelTier): List<ModelInfo> = all.filter { it.tier == tier }
}