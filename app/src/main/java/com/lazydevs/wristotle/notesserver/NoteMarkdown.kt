// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure text helpers for editing rust_note markdown. Checkbox detection
 * mirrors the server/web parser: `- [ ]`, `* [x]`, `+ [m]`… with the
 * markers ` xXoOmM`; locating a task prefers the reported line but
 * verifies the text, falling back to the first line with matching text
 * (same strategy as rust_note's web `todoToggle.ts`).
 */
object NoteMarkdown {
    private val CHECKBOX = Regex("""^(\s*[-*+][ \t]+\[)[ xXoOmM]]""")
    private val DAILY_NOTE = Regex("""^diary/\d{4}-\d{2}-\d{2}$""")
    const val DAILY_TEMPLATE = "---\nplan: true\n---\n"

    fun isDailyNote(id: String): Boolean = DAILY_NOTE.matches(id)

    fun dailyNoteId(date: LocalDate): String = "diary/$date"

    /** Whether a server note is shown as a note in Wristotle. Daily notes are
     *  the task lists (surfaced through Tasks), drawings aren't markdown and
     *  `_settings/` is app-internal. */
    fun isSyncableNote(id: String): Boolean =
        !isDailyNote(id) && !id.endsWith(".excalidraw") && !id.startsWith("_")

    /** Body as shown in Wristotle: YAML frontmatter dropped, outer whitespace trimmed. */
    fun displayBody(content: String): String {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return normalized.trim()
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return normalized.trim()
        val afterFence = normalized.indexOf('\n', end + 4).let { if (it < 0) normalized.length else it + 1 }
        return normalized.substring(afterFence).trim()
    }

    /** Same shape as the local append (`"$old\n$new"`), with a trailing newline. */
    fun appendText(content: String, text: String): String {
        val base = content.trimEnd('\n', ' ', '\t')
        return if (base.isEmpty()) "$text\n" else "$base\n$text\n"
    }

    /** The `POST /api/notes` title for a new note: dated, first few words, in the Wristotle folder. */
    fun newNoteTitle(body: String, createdAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(zone).format(Instant.ofEpochMilli(createdAtMs))
        val words = body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .split(Regex("\\s+")).filter { it.any(Char::isLetterOrDigit) }.take(6).joinToString(" ")
        return "${NotesServerConfig.WRISTOTLE_FOLDER}/$stamp $words".trimEnd()
    }

    /** Task text is a single markdown line. */
    fun taskLineText(text: String): String = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()

    fun taskLine(text: String, done: Boolean): String = "- [${if (done) 'x' else ' '}] ${taskLineText(text)}"

    /** Column of the marker char inside `[…]`, or null when [line] isn't a task. */
    fun checkboxCol(line: String): Int? = CHECKBOX.find(line)?.groups?.get(1)?.value?.length

    /** Text after the `]`, left-trimmed. */
    fun taskText(line: String, col: Int): String = line.substring(col + 2).trimStart()

    /** 0-based index of the task line, or null when it's gone. */
    fun locate(lines: List<String>, lineHint: Int, rawText: String): Int? {
        val want = rawText.trim()
        fun matches(i: Int): Boolean {
            if (i !in lines.indices) return false
            val col = checkboxCol(lines[i]) ?: return false
            return taskText(lines[i], col).trim() == want
        }
        if (matches(lineHint - 1)) return lineHint - 1
        return lines.indices.firstOrNull(::matches)
    }

    /** Flips the checkbox; null when the task can't be found. */
    fun setDone(content: String, lineHint: Int, rawText: String, done: Boolean): String? {
        val lines = content.split('\n').toMutableList()
        val i = locate(lines, lineHint, rawText) ?: return null
        val col = checkboxCol(lines[i]) ?: return null
        val marker = if (done) 'x' else ' '
        lines[i] = lines[i].substring(0, col) + marker + lines[i].substring(col + 1)
        return lines.joinToString("\n")
    }

    /** Removes the task line; null when the task can't be found. */
    fun removeTask(content: String, lineHint: Int, rawText: String): String? {
        val lines = content.split('\n').toMutableList()
        val i = locate(lines, lineHint, rawText) ?: return null
        lines.removeAt(i)
        return lines.joinToString("\n")
    }

    /**
     * Appends one checkbox line per task. Returns the new content and the
     * 1-based line number of each appended task, in order.
     */
    fun appendTasks(content: String, tasks: List<Pair<String, Boolean>>): Pair<String, List<Int>> {
        val base = content.trimEnd('\n')
        val lines = if (base.isEmpty()) mutableListOf() else base.split('\n').toMutableList()
        val numbers = tasks.map { (text, done) ->
            lines += taskLine(text, done)
            lines.size
        }
        return (lines.joinToString("\n") + "\n") to numbers
    }
}
