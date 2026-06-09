// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.fs

/**
 * Platform-agnostic file write / read / delete surface for the file
 * sync pipeline. Android impl wraps Storage Access Framework's
 * `DocumentFile` (the user picks a vault folder via SAF; we get a
 * `treeUri` and operate inside it). An iOS impl would wrap
 * `NSFileManager` with `UIDocumentPickerViewController` providing the
 * folder root.
 *
 * Paths are slash-delimited and relative to the user-picked root —
 * `"notes/abc.md"`, `"attachments/2026-06-08T12-34.wav"`. The impl is
 * responsible for translating that into platform-specific document /
 * file APIs.
 *
 * R4 batch 8 — interface added; existing :app/sync/FileSyncCoordinator
 * keeps its direct DocumentFile + ContentResolver usage. The
 * abstraction is here to document the seam an iOS port will satisfy,
 * not to force a refactor of the Android-deep SAF code path that
 * gains little from the indirection.
 */
interface FileSystem {

    /** Write [content] to [relativePath], overwriting any existing file. */
    suspend fun writeText(relativePath: String, content: String): Boolean

    /** Write a binary payload. Used for the attached audio files in
     *  the notes sync flow. */
    suspend fun writeBytes(relativePath: String, content: ByteArray): Boolean

    /** Read a file as text. Returns null when the file is absent / unreadable. */
    suspend fun readText(relativePath: String): String?

    /** Delete a file. No-op when absent; returns false on permission denial. */
    suspend fun delete(relativePath: String): Boolean

    /** Whether a file exists at [relativePath]. */
    suspend fun exists(relativePath: String): Boolean

    /** List file paths within [relativeDir]. Returns empty when the dir
     *  is absent. Paths are returned RELATIVE to the configured root,
     *  matching the path format the other methods accept. */
    suspend fun list(relativeDir: String): List<String>
}
