// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormat
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncGranularity
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormatOptions
import com.lazydevs.wristotle.tasks.TaskEntity
import java.time.Instant

/**
 * [TaskEntity] → [RenderedEntry] for the file-sync pipeline — the tasks
 * equivalent of [NoteRenderer]. No attachments.
 *
 * Layout mirrors notes: everything under `Wristotle/tasks/`.
 *
 * - **AppendToSingleFile** (the default for tasks) — every task renders to a
 *   one-line fragment at `Wristotle/tasks/tasks.md`; the coordinator
 *   concatenates them and atomically rewrites the file each pass, so the doc
 *   is a live checklist that reflects current state (a completed task flips to
 *   `- [x]`) rather than an append-only log that would duplicate on edit.
 * - **OneFilePerEntity** — `Wristotle/tasks/<id>.md` per task, Overwrite; for
 *   users who want a file each.
 *
 * Pure — no Android imports — so it's unit-testable with synthetic tasks.
 */
class TaskRenderer : FileSyncRenderer<TaskEntity> {
    override fun render(entity: TaskEntity, options: FileSyncFormatOptions): RenderedEntry {
        return when (options.granularity) {
            FileSyncGranularity.AppendToSingleFile -> RenderedEntry(
                relativePath = "$VAULT_ROOT/tasks.${extensionFor(options.format)}",
                content = when (options.format) {
                    FileSyncFormat.Markdown -> checklistLine(entity)
                    FileSyncFormat.PlainText -> plainLine(entity)
                    FileSyncFormat.Json -> renderJson(entity)
                },
                mode = WriteMode.Append,
            )
            FileSyncGranularity.OneFilePerEntity -> RenderedEntry(
                relativePath = "$VAULT_ROOT/${entity.id}.${extensionFor(options.format)}",
                content = when (options.format) {
                    FileSyncFormat.Markdown -> renderMarkdownDoc(entity)
                    FileSyncFormat.PlainText -> plainLine(entity)
                    FileSyncFormat.Json -> renderJson(entity)
                },
                mode = WriteMode.Overwrite,
            )
        }
    }

    /** GitHub-Flavoured-Markdown task line: `- [ ] text` / `- [x] text`. */
    private fun checklistLine(t: TaskEntity): String =
        "- [${if (t.completed) "x" else " "}] ${t.text}"

    private fun plainLine(t: TaskEntity): String =
        "[${if (t.completed) "x" else " "}] ${t.text}"

    private fun renderMarkdownDoc(t: TaskEntity): String = buildString {
        appendLine("---")
        appendLine("id: ${t.id}")
        appendLine("status: ${if (t.completed) "done" else "pending"}")
        appendLine("created_at: ${Instant.ofEpochMilli(t.createdAtEpochMs)}")
        if (t.completedAtEpochMs != null) {
            appendLine("completed_at: ${Instant.ofEpochMilli(t.completedAtEpochMs)}")
        }
        appendLine("source: ${t.source}")
        appendLine("---")
        appendLine()
        appendLine(checklistLine(t))
    }

    private fun renderJson(t: TaskEntity): String = buildString {
        append('{')
        append("\"id\":${t.id},")
        append("\"text\":\"${escapeJson(t.text)}\",")
        append("\"completed\":${t.completed},")
        append("\"createdAt\":\"${Instant.ofEpochMilli(t.createdAtEpochMs)}\",")
        append("\"completedAt\":")
        if (t.completedAtEpochMs != null) append("\"${Instant.ofEpochMilli(t.completedAtEpochMs)}\"") else append("null")
        append(",\"source\":\"${escapeJson(t.source)}\"")
        append('}')
    }

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
        const val VAULT_ROOT = "Wristotle/tasks"
    }
}
