// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.sync

import java.io.File

/**
 * The portable description of one rendered entity as it should land on
 * disk. Pure-data — produced by a [FileSyncRenderer] and consumed by
 * the [FileSyncCoordinator] which holds the SAF-aware write logic.
 *
 * Three knobs make this generic across entity types (Notes today,
 * Conversations later):
 *
 * - [relativePath] — where inside the user's chosen vault root the
 *   payload goes. Sub-directories are part of the path (e.g.
 *   `Wristotle/notes/<id>.md`) so the renderer controls the layout.
 * - [content] — the rendered string (Markdown / plain text / JSON).
 *   Format is the renderer's call; settings pass it through.
 * - [attachments] — sibling binaries (audio, in the Notes case). Each
 *   carries its own relative path so the renderer can group them in a
 *   sub-directory like `Wristotle/notes/attachments/<basename>.wav`.
 * - [mode] — Overwrite (one-file-per-entity) vs Append (single-file
 *   daily log). The coordinator decides how to realise each on SAF;
 *   the renderer just declares intent.
 */
data class RenderedEntry(
    val relativePath: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val mode: WriteMode = WriteMode.Overwrite,
)

/**
 * One file to copy alongside the rendered entity. [sourceFile] is the
 * app's on-disk source (e.g. `filesDir/notes-audio/<basename>.wav`),
 * [relativePath] is where it should land in the vault. The coordinator
 * skips the copy if a file with the same path already exists and matches
 * by length + mtime; full content compare is overkill for our sizes.
 */
data class Attachment(
    val sourceFile: File,
    val relativePath: String,
)

enum class WriteMode { Overwrite, Append }

/**
 * Generic renderer — one implementation per entity type. The coordinator
 * stays entity-agnostic so adding Conversations later is just a new
 * `ConversationRenderer : FileSyncRenderer<ConversationEntry>` and a
 * coordinator instance pointed at that flow.
 *
 * Renderers are pure — no SAF / file IO. That keeps them unit-testable
 * with synthetic entities and synthetic format options.
 */
interface FileSyncRenderer<T> {
    fun render(entity: T, options: FileSyncFormatOptions): RenderedEntry
}

/**
 * Per-render flags the user picked in Settings. Passed through to the
 * renderer so it can choose markdown vs plain text vs JSON and one-file
 * vs append. Kept as a value class rather than two enums in the
 * renderer signature so future knobs (Obsidian-flavour wikilinks,
 * frontmatter on/off, etc.) land without breaking callers.
 */
data class FileSyncFormatOptions(
    val format: FileSyncFormat = FileSyncFormat.Markdown,
    val granularity: FileSyncGranularity = FileSyncGranularity.OneFilePerEntity,
)

/**
 * Wire format for the rendered payload. CommonMark-clean Markdown is
 * the default; tool-specific syntax (Obsidian `[[…]]` wikilinks,
 * Logseq block refs, Joplin `:/id` resource refs, etc.) is deliberately
 * NOT supported by default to keep one canonical baseline that
 * round-trips across every tool.
 */
enum class FileSyncFormat { Markdown, PlainText, Json }

enum class FileSyncGranularity { OneFilePerEntity, AppendToSingleFile }

/**
 * Outcome of the most recent sync pass — published by the coordinator
 * on every pass (reactive or manual). UI reads this to render the
 * small status line under the "Sync now" button.
 */
sealed interface LastSyncResult {
    data class Success(val wrote: Int, val removed: Int, val atEpochMs: Long) : LastSyncResult
    data class Failed(val message: String) : LastSyncResult

    /** Sync was requested but skipped because either it's disabled or
     *  no folder is picked. Distinct from [Failed] so the UI can show
     *  "pick a folder first" rather than an error chip. */
    data object Skipped : LastSyncResult
}