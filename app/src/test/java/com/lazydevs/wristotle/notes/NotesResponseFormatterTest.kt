package com.lazydevs.wristotle.notes

import com.lazydevs.wristotle.transport.MessageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesResponseFormatterTest {

    private fun note(body: String, ts: Long = 0L): Note =
        Note(body = body, createdAtEpochMs = ts, source = "watch")

    @Test fun `empty list yields empty payload`() {
        assertEquals("", NotesResponseFormatter.format(emptyList()))
    }

    @Test fun `single short note round-trips verbatim`() {
        assertEquals("pick up milk", NotesResponseFormatter.format(listOf(note("pick up milk"))))
    }

    @Test fun `notes are joined by record separator`() {
        val out = NotesResponseFormatter.format(listOf(note("first"), note("second"), note("third")))
        assertEquals("first${MessageKeys.NOTES_SEPARATOR}second${MessageKeys.NOTES_SEPARATOR}third", out)
    }

    @Test fun `newlines and CRs in body are collapsed to single spaces`() {
        // Watch renders one line per MenuLayer row; embedded newlines would split awkwardly.
        val out = NotesResponseFormatter.format(listOf(note("line one\nline two\rline three")))
        assertEquals("line one line two line three", out)
    }

    @Test fun `body longer than MAX_BODY_CHARS is truncated with ellipsis`() {
        val long = "a".repeat(NotesResponseFormatter.MAX_BODY_CHARS + 50)
        val out = NotesResponseFormatter.format(listOf(note(long)))
        assertEquals(NotesResponseFormatter.MAX_BODY_CHARS, out.length)
        assertTrue("ends with ellipsis", out.endsWith("…"))
    }

    @Test fun `at most MAX_NOTES are included`() {
        val notes = List(NotesResponseFormatter.MAX_NOTES + 3) { i -> note("note$i") }
        val out = NotesResponseFormatter.format(notes)
        val parts = out.split(MessageKeys.NOTES_SEPARATOR)
        assertEquals(NotesResponseFormatter.MAX_NOTES, parts.size)
    }

    @Test fun `payload stays under MAX_TOTAL_CHARS`() {
        // Six max-length notes would otherwise be > MAX_TOTAL_CHARS; the
        // formatter drops the tail rather than overshoot.
        val long = "x".repeat(NotesResponseFormatter.MAX_BODY_CHARS)
        val notes = List(NotesResponseFormatter.MAX_NOTES) { note(long) }
        val out = NotesResponseFormatter.format(notes)
        assertTrue(
            "length ${out.length} under cap ${NotesResponseFormatter.MAX_TOTAL_CHARS}",
            out.length <= NotesResponseFormatter.MAX_TOTAL_CHARS,
        )
        // And we should have included at least 4 notes (4 * (120 + 1 sep) = 484 < 580).
        val parts = out.split(MessageKeys.NOTES_SEPARATOR)
        assertTrue("included at least 4 notes (got ${parts.size})", parts.size >= 4)
    }

    @Test fun `blank-body notes are skipped`() {
        val out = NotesResponseFormatter.format(listOf(note("  "), note("real note")))
        assertFalse("payload doesn't contain a separator", out.contains(MessageKeys.NOTES_SEPARATOR))
        assertEquals("real note", out)
    }
}
