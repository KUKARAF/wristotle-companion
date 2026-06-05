package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteAudioPaths
import java.io.File
import java.time.Instant

/**
 * [Note] → [RenderedEntry] for the file-sync pipeline.
 *
 * Layout (user-confirmed via codeberg issue #5):
 * - **Sub-directory**: every payload lives under `Wristotle/notes/` so
 *   the user's other vault content stays untouched.
 * - **Stable id filename**: `<note-id>.md` — survives title edits
 *   without churning the on-disk file. The frontmatter still carries a
 *   human-readable `title` slug for the file browser.
 * - **Audio attachments**: copied (not moved) to
 *   `Wristotle/notes/attachments/<basename>.wav`. Markdown body links
 *   each one with the universal CommonMark form
 *   `[Audio recording N](attachments/<basename>.wav)` — no Obsidian
 *   wikilink syntax, so every tool reading the folder shows a working
 *   link.
 *
 * Three output formats:
 * - `Markdown` — YAML frontmatter + body + audio links. Default.
 * - `PlainText` — header line + body + `[Audio: <basename>.wav]`
 *   markers. For non-markdown apps.
 * - `Json` — structured `{id, createdAt, body, attachments}` for
 *   scripting / external tooling.
 *
 * Two granularities:
 * - `OneFilePerEntity` — `Wristotle/notes/<ISO timestamp>.md` per note,
 *   Overwrite.
 * - `AppendToSingleFile` — the renderer still returns per-entity
 *   fragments at `Wristotle/notes/notes.md` (inside the entity folder
 *   alongside attachments/) with `mode = Append`; the coordinator
 *   concatenates all fragments and atomically rewrites the file.
 *   (Per-fragment with mode=Append keeps the renderer stateless; the
 *   coordinator owns the "all fragments at once" view.)
 *
 * Pure — no Android imports — so the test layer can exercise every
 * branch with synthetic `Note` instances. Audio attachment files are
 * referenced by `File` path; the coordinator does the actual SAF copy.
 */
class NoteRenderer : FileSyncRenderer<Note> {
    override fun render(entity: Note, options: FileSyncFormatOptions): RenderedEntry {
        val audioFiles = NoteAudioPaths.parse(entity.audioFilePath).map { File(it) }
        val attachments = audioFiles.map { src ->
            Attachment(
                sourceFile = src,
                relativePath = "$VAULT_ROOT/$ATTACHMENTS_DIR/${src.name}",
            )
        }
        val body = when (options.format) {
            FileSyncFormat.Markdown -> renderMarkdown(entity, audioFiles)
            FileSyncFormat.PlainText -> renderPlainText(entity, audioFiles)
            FileSyncFormat.Json -> renderJson(entity, audioFiles)
        }
        return when (options.granularity) {
            FileSyncGranularity.OneFilePerEntity -> RenderedEntry(
                relativePath = "$VAULT_ROOT/${filenameFor(entity)}.${extensionFor(options.format)}",
                content = body,
                attachments = attachments,
                mode = WriteMode.Overwrite,
            )
            FileSyncGranularity.AppendToSingleFile -> RenderedEntry(
                // Inside the entity folder, not as a sibling: keeps the
                // daily-log file + attachments/ grouped under
                // Wristotle/notes/.
                relativePath = "$VAULT_ROOT/notes.${extensionFor(options.format)}",
                content = body,
                attachments = attachments,
                mode = WriteMode.Append,
            )
        }
    }

    /**
     * Filesystem-safe ISO timestamp from the note's createdAtEpochMs:
     * `2026-06-05T17-30-21-123Z`. Colons + dots in the standard ISO
     * format (`2026-06-05T17:30:21.123Z`) are invalid on FAT-based
     * sync targets (some cloud-mounted folders) and noisy in URLs;
     * dashes round-trip cleanly everywhere.
     *
     * Why timestamp-not-id: stable across fresh installs. If the user
     * wipes app data and Room hands out id=1 to a new note, the file
     * lands at a timestamp that didn't exist in the previous install
     * — no collision with the old `<old-timestamp>.md` files in the
     * vault. Within-install collisions on the same millisecond are
     * astronomical at human dictation rates (≥1s gaps); if a burst
     * ever does collide, the second write overwrites the first, which
     * is acceptable for the use case.
     */
    private fun filenameFor(note: Note): String =
        Instant.ofEpochMilli(note.createdAtEpochMs).toString()
            .replace(':', '-')
            .replace('.', '-')
            .removeSuffix("-")

    private fun renderMarkdown(note: Note, audio: List<File>): String = buildString {
        appendLine("---")
        appendLine("id: ${note.id}")
        appendLine("created_at: ${Instant.ofEpochMilli(note.createdAtEpochMs)}")
        appendLine("source: ${note.source}")
        appendLine("---")
        appendLine()
        appendLine(note.body)
        if (audio.isNotEmpty()) {
            appendLine()
            // Link text = filename so the reader can identify the audio
            // even when the markdown is viewed out of context (search,
            // grep, copy-paste, vault tools that ignore link resolution).
            audio.forEach { f ->
                appendLine("[${f.name}]($ATTACHMENTS_DIR/${f.name})")
            }
        }
    }

    private fun renderPlainText(note: Note, audio: List<File>): String = buildString {
        appendLine("${Instant.ofEpochMilli(note.createdAtEpochMs)} — note ${note.id}")
        appendLine()
        appendLine(note.body)
        if (audio.isNotEmpty()) {
            appendLine()
            audio.forEach { f -> appendLine("[Audio: ${f.name}]") }
        }
    }

    // Hand-rolled JSON keeps the renderer dependency-free + the format
    // is small enough that escaping a couple of chars is cleaner than
    // dragging kotlinx.serialization annotations through Note.
    private fun renderJson(note: Note, audio: List<File>): String = buildString {
        append('{')
        append("\"id\":${note.id},")
        append("\"createdAt\":\"${Instant.ofEpochMilli(note.createdAtEpochMs)}\",")
        append("\"source\":\"${escapeJson(note.source)}\",")
        append("\"body\":\"${escapeJson(note.body)}\",")
        append("\"attachments\":[")
        audio.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("\"$ATTACHMENTS_DIR/${escapeJson(f.name)}\"")
        }
        append("]}")
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
        const val VAULT_ROOT = "Wristotle/notes"
        const val ATTACHMENTS_DIR = "attachments"
    }
}
