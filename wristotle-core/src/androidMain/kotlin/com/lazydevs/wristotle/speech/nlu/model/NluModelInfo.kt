// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.model

/**
 * Metadata describing a downloadable sentence-encoder model for the NLU
 * intent classifier.
 *
 * Mirrors the Whisper `ModelInfo` shape so the same UX (catalog → download
 * → activate → delete) can be reused with minimal divergence.
 */
data class NluModelInfo(
    /** Stable id used as the filename suffix (`minilm-l6-v2-int8` → `minilm-{id}.onnx`). */
    val id: String,
    /** User-facing display name in the model picker. */
    val displayName: String,
    /** Approximate download size in bytes. */
    val approxSizeBytes: Long,
    /** Architecture label for the UI (e.g. "MiniLM-L6"). */
    val architectureLabel: String,
    /** Resolvable HTTPS URL to the `.onnx` file. */
    val url: String,
)