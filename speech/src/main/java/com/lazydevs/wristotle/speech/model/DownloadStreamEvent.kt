// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.model

/**
 * Stream of events from [ResumableDownloader.download]. Shared across
 * every downloadable model family (Whisper, NLU MiniLM, etc.) — each
 * family used to define its own structurally-identical sealed type, and
 * the duplication was producing real divergence (e.g. one had richer
 * progress tracking the other didn't pick up).
 */
sealed interface DownloadStreamEvent {
    /**
     * Periodic progress update. [totalBytes] is null when the server
     * didn't advertise a length (UI should show indeterminate progress).
     */
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long?) : DownloadStreamEvent

    /** Terminal — the file is fully downloaded and ready to load. */
    data object Complete : DownloadStreamEvent

    /**
     * Terminal — download failed. The partial file remains on disk so
     * the next attempt can resume via HTTP Range.
     */
    data class Failed(val message: String, val cause: Throwable? = null) : DownloadStreamEvent
}