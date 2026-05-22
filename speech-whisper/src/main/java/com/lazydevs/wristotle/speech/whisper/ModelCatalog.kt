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

    val all: List<ModelInfo> = listOf(
        // ── Recommended: one per tier, the curated default picker. ──
        // Quantized (q5_1) variants — ~50–60% the disk + memory footprint of
        // the full models with negligible accuracy loss on short commands.
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
        ModelInfo(
            id = "small.en",
            displayName = "Small (English)",
            approxSizeBytes = 466_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-small.en.bin",
            tier = ModelTier.ACCURATE,
            recommended = true,
        ),

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
