package com.lazydevs.wristotle.speech.whisper

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
    // TODO: SHA-256 for integrity verification — skipped in v1; downloads
    //       only trust HuggingFace's HTTPS transport for now.
)

/** Known Whisper models the user can download from this app. */
object ModelCatalog {

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val all: List<ModelInfo> = listOf(
        ModelInfo(
            id = "tiny.en",
            displayName = "Tiny (English)",
            approxSizeBytes = 77_700_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-tiny.en.bin",
        ),
        ModelInfo(
            id = "base.en",
            displayName = "Base (English)",
            approxSizeBytes = 142_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-base.en.bin",
        ),
        ModelInfo(
            id = "base",
            displayName = "Base (Multilingual)",
            approxSizeBytes = 142_000_000L,
            languageLabel = "Multilingual",
            url = "$HF_BASE/ggml-base.bin",
        ),
        ModelInfo(
            id = "small.en",
            displayName = "Small (English)",
            approxSizeBytes = 466_000_000L,
            languageLabel = "English",
            url = "$HF_BASE/ggml-small.en.bin",
        ),
    )

    fun byId(id: String): ModelInfo? = all.firstOrNull { it.id == id }
}
