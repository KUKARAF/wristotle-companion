// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class NoteMarkdownTest {

    private val daily = "---\nplan: true\n---\n- [ ] buy milk\n- [x] call mum\n  - [m] nested #fb\n"

    @Test fun `display body strips frontmatter`() {
        assertEquals("- [ ] buy milk\n- [x] call mum\n  - [m] nested #fb", NoteMarkdown.displayBody(daily))
        assertEquals("plain", NoteMarkdown.displayBody("\nplain\n"))
    }

    @Test fun `syncable notes exclude daily notes drawings and settings`() {
        assertTrue(NoteMarkdown.isSyncableNote("wristotle/2026-09-28-hello"))
        assertTrue(NoteMarkdown.isSyncableNote("projects/rust-note"))
        assertFalse(NoteMarkdown.isSyncableNote("diary/2026-09-28"))
        assertFalse(NoteMarkdown.isSyncableNote("sketch.excalidraw"))
        assertFalse(NoteMarkdown.isSyncableNote("_settings/app"))
    }

    @Test fun `set done uses line hint and verifies text`() {
        val out = NoteMarkdown.setDone(daily, lineHint = 4, rawText = "buy milk", done = true)
        assertEquals(daily.replace("- [ ] buy milk", "- [x] buy milk"), out)
    }

    @Test fun `set done falls back to text search when lines shifted`() {
        val shifted = "# added heading\n$daily"
        val out = NoteMarkdown.setDone(shifted, lineHint = 5, rawText = "call mum", done = false)
        assertEquals(shifted.replace("- [x] call mum", "- [ ] call mum"), out)
    }

    @Test fun `nested and carry-over markers are found`() {
        val out = NoteMarkdown.setDone(daily, lineHint = 99, rawText = "nested #fb", done = true)
        assertEquals(daily.replace("  - [m] nested #fb", "  - [x] nested #fb"), out)
    }

    @Test fun `missing task returns null`() {
        assertNull(NoteMarkdown.setDone(daily, 4, "not there", true))
        assertNull(NoteMarkdown.removeTask(daily, 4, "not there"))
    }

    @Test fun `remove task drops the line`() {
        assertEquals(daily.replace("- [x] call mum\n", ""), NoteMarkdown.removeTask(daily, 5, "call mum"))
    }

    @Test fun `append tasks reports 1-based line numbers`() {
        val (content, lines) = NoteMarkdown.appendTasks(daily, listOf("a\nb" to false, "done" to true))
        assertEquals(daily + "- [ ] a b\n- [x] done\n", content)
        assertEquals(listOf(7, 8), lines)
        val (fresh, freshLines) = NoteMarkdown.appendTasks("", listOf("x" to false))
        assertEquals("- [ ] x\n", fresh)
        assertEquals(listOf(1), freshLines)
    }

    @Test fun `append text`() {
        assertEquals("one\ntwo\n", NoteMarkdown.appendText("one\n\n", "two"))
        assertEquals("two\n", NoteMarkdown.appendText("", "two"))
    }

    @Test fun `new note title is dated and in the wristotle folder`() {
        val title = NoteMarkdown.newNoteTitle("Buy oat milk, eggs and bread today please!\nmore", 0L, ZoneOffset.UTC)
        assertEquals("wristotle/1970-01-01 000000 Buy oat milk, eggs and bread", title)
    }
}
