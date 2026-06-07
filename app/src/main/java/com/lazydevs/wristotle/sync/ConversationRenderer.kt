// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.history.ConversationEntry
import java.io.File
import java.time.Instant

/**
 * [ConversationEntry] → [RenderedEntry] for the file-sync pipeline.
 *
 * Mirrors [NoteRenderer]'s shape — same three output formats, same two
 * granularities, same `application/octet-stream` MIME story handled by
 * the coordinator. The only entity-specific decisions:
 *
 * - **Sub-directory**: `Wristotle/conversations/` for one-file-per-
 *   entity, or `Wristotle/conversations.<ext>` for the append-to-single-
 *   file daily-log shape that this entity defaults to in Settings.
 * - **Filename** (per-entry mode): filesystem-safe ISO timestamp from
 *   `timestampEpochMs` — `2026-06-05T17-30-21-123Z.md`. Same rationale
 *   as [NoteRenderer]: timestamps are unique-by-construction across
 *   installs, no collision logic needed.
 * - **Body shape**: `Q: <userQuery>\nA: <responseText>` — the agent-
 *   transcript convention. Markdown additionally wraps with YAML
 *   frontmatter carrying `id` / `timestamp` / `handler` / `intent` /
 *   `confidence` / `success`, plus optional audio link.
 *
 * Pure — no Android imports — fully unit-testable. The audio file
 * lookup uses `java.io.File` only for path mechanics; the coordinator
 * does the actual SAF copy.
 */
class ConversationRenderer : FileSyncRenderer<ConversationEntry> {
    override fun render(entity: ConversationEntry, options: FileSyncFormatOptions): RenderedEntry {
        val audioFile = entity.audioFilePath?.let(::File)?.takeIf { it.path.isNotBlank() }
        val attachments = audioFile?.let {
            listOf(
                Attachment(
                    sourceFile = it,
                    relativePath = "$VAULT_ROOT/$ATTACHMENTS_DIR/${it.name}",
                ),
            )
        }.orEmpty()
        val body = when (options.format) {
            FileSyncFormat.Markdown -> renderMarkdown(entity, audioFile)
            FileSyncFormat.PlainText -> renderPlainText(entity, audioFile)
            FileSyncFormat.Json -> renderJson(entity, audioFile)
        }
        return when (options.granularity) {
            FileSyncGranularity.OneFilePerEntity -> RenderedEntry(
                relativePath = "$VAULT_ROOT/${filenameFor(entity)}.${extensionFor(options.format)}",
                content = body,
                attachments = attachments,
                mode = WriteMode.Overwrite,
            )
            FileSyncGranularity.AppendToSingleFile -> RenderedEntry(
                // Inside the entity folder, not as a sibling: keeps
                // every conversation artefact (daily-log file +
                // attachments/) grouped under Wristotle/conversations/.
                relativePath = "$VAULT_ROOT/conversations.${extensionFor(options.format)}",
                content = body,
                attachments = attachments,
                mode = WriteMode.Append,
            )
        }
    }

    private fun renderMarkdown(entry: ConversationEntry, audio: File?): String = buildString {
        appendLine("---")
        appendLine("id: ${entry.id}")
        appendLine("timestamp: ${Instant.ofEpochMilli(entry.timestampEpochMs)}")
        appendLine("handler: ${entry.handler}")
        appendLine("success: ${entry.success}")
        entry.nluIntent?.let { appendLine("intent: $it") }
        entry.nluConfidence?.let { appendLine("confidence: $it") }
        appendLine("---")
        appendLine()
        // Daily-log-friendly heading so AppendToSingleFile concatenations
        // are scannable as the file grows. In OneFilePerEntity mode this
        // duplicates the filename slightly, but readers expect a top-level
        // heading anyway.
        appendLine("## ${Instant.ofEpochMilli(entry.timestampEpochMs)}")
        appendLine()
        appendLine("**Q:** ${entry.userQuery}")
        appendLine()
        appendLine("**A:** ${entry.responseText}")
        if (audio != null) {
            appendLine()
            // Link text = filename so the reader can identify the audio
            // even when the markdown is viewed out of context.
            appendLine("[${audio.name}]($ATTACHMENTS_DIR/${audio.name})")
        }
    }

    private fun renderPlainText(entry: ConversationEntry, audio: File?): String = buildString {
        appendLine("${Instant.ofEpochMilli(entry.timestampEpochMs)} — ${entry.handler}")
        appendLine("Q: ${entry.userQuery}")
        appendLine("A: ${entry.responseText}")
        if (audio != null) {
            appendLine("[Audio: ${audio.name}]")
        }
    }

    private fun renderJson(entry: ConversationEntry, audio: File?): String = buildString {
        append('{')
        append("\"id\":${entry.id},")
        append("\"timestamp\":\"${Instant.ofEpochMilli(entry.timestampEpochMs)}\",")
        append("\"handler\":\"${escapeJson(entry.handler)}\",")
        append("\"success\":${entry.success},")
        entry.nluIntent?.let { append("\"intent\":\"${escapeJson(it)}\",") }
        entry.nluConfidence?.let { append("\"confidence\":$it,") }
        append("\"userQuery\":\"${escapeJson(entry.userQuery)}\",")
        append("\"responseText\":\"${escapeJson(entry.responseText)}\",")
        append("\"attachments\":[")
        if (audio != null) {
            append("\"$ATTACHMENTS_DIR/${escapeJson(audio.name)}\"")
        }
        append("]}")
    }

    private fun filenameFor(entry: ConversationEntry): String =
        Instant.ofEpochMilli(entry.timestampEpochMs).toString()
            .replace(':', '-')
            .replace('.', '-')
            .removeSuffix("-")

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun extensionFor(f: FileSyncFormat): String = when (f) {
        FileSyncFormat.Markdown -> "md"
        FileSyncFormat.PlainText -> "txt"
        FileSyncFormat.Json -> "json"
    }

    private companion object {
        const val VAULT_ROOT = "Wristotle/conversations"
        const val ATTACHMENTS_DIR = "attachments"
    }
}