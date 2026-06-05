package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.notes.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRendererTest {

    private val renderer = NoteRenderer()

    private fun note(
        id: Long = 1,
        body: String = "Buy groceries",
        audio: String? = null,
        createdAtEpochMs: Long = 1_700_000_000_000L,
        source: String = "watch",
    ) = Note(
        id = id,
        body = body,
        createdAtEpochMs = createdAtEpochMs,
        source = source,
        audioFilePath = audio,
    )

    @Test fun `markdown one-file-per-note writes to Wristotle slash notes slash ISO timestamp dot md`() {
        // 1_700_000_000_000 ms = 2023-11-14T22-13-20Z
        val entry = renderer.render(note(id = 42), FileSyncFormatOptions())
        assertEquals("Wristotle/notes/2023-11-14T22-13-20Z.md", entry.relativePath)
        assertEquals(WriteMode.Overwrite, entry.mode)
    }

    @Test fun `filename swaps colons and dots for dashes so all file systems are happy`() {
        val createdAtMs = 1_717_612_345_678L // 2024-06-05T18-32-25-678Z
        val entry = renderer.render(
            note(createdAtEpochMs = createdAtMs),
            FileSyncFormatOptions(),
        )
        assertEquals("Wristotle/notes/2024-06-05T18-32-25-678Z.md", entry.relativePath)
        // None of these chars should ever leak through to the path —
        // they break FAT/exFAT and are noisy in URL form.
        assertFalse(entry.relativePath.contains(':'))
    }

    @Test fun `markdown body contains YAML frontmatter with id and full body`() {
        // No synthetic `title:` — Notes have no title field, and the first
        // line of the body is already in the body itself. Frontmatter is
        // intentionally minimal so the rendered file stays close to what
        // the user actually typed.
        val entry = renderer.render(note(id = 7, body = "Buy groceries\nand milk"), FileSyncFormatOptions())
        assertTrue(entry.content.startsWith("---\n"))
        assertTrue(entry.content.contains("id: 7"))
        assertFalse(entry.content.contains("title:"))
        assertTrue(entry.content.contains("Buy groceries\nand milk"))
    }

    @Test fun `markdown uses CommonMark link with filename as text — no Obsidian wikilinks`() {
        val entry = renderer.render(
            note(audio = "/data/data/.../notes-audio/note-1-001.wav"),
            FileSyncFormatOptions(),
        )
        // CommonMark standard: [text](path). The text is the filename
        // itself so readers can identify the audio file even when the
        // markdown is viewed out of context. Obsidian's `![[…]]` MUST
        // NOT appear — keeps the output universal.
        assertTrue(entry.content.contains("[note-1-001.wav](attachments/note-1-001.wav)"))
        assertFalse(entry.content.contains("![["))
    }

    @Test fun `audio attachments target the attachments sub-directory`() {
        val entry = renderer.render(
            note(audio = "/x/notes-audio/note-9-001.wav"),
            FileSyncFormatOptions(),
        )
        assertEquals(1, entry.attachments.size)
        assertEquals("Wristotle/notes/attachments/note-9-001.wav", entry.attachments[0].relativePath)
    }

    @Test fun `multiple audio files render multiple link lines + attachments`() {
        // NoteAudioPaths uses US (0x1F) as separator
        val audio = "/x/notes-audio/note-1-001.wav/x/notes-audio/note-1-002.wav"
        val entry = renderer.render(note(audio = audio), FileSyncFormatOptions())
        assertEquals(2, entry.attachments.size)
        assertTrue(entry.content.contains("[note-1-001.wav](attachments/note-1-001.wav)"))
        assertTrue(entry.content.contains("[note-1-002.wav](attachments/note-1-002.wav)"))
    }

    @Test fun `plain text format uses bracket marker not link syntax`() {
        val entry = renderer.render(
            note(audio = "/x/notes-audio/a.wav"),
            FileSyncFormatOptions(format = FileSyncFormat.PlainText),
        )
        assertEquals("Wristotle/notes/2023-11-14T22-13-20Z.txt", entry.relativePath)
        assertTrue(entry.content.contains("[Audio: a.wav]"))
        assertFalse(entry.content.contains("---"))  // no YAML frontmatter
    }

    @Test fun `json format produces parseable structured output`() {
        val entry = renderer.render(
            note(id = 5, body = "Hello \"world\"\nLine 2", audio = "/x/notes-audio/a.wav"),
            FileSyncFormatOptions(format = FileSyncFormat.Json),
        )
        assertEquals("Wristotle/notes/2023-11-14T22-13-20Z.json", entry.relativePath)
        // Sanity-check the structure without dragging in a json parser.
        // The id is still in the JSON body — the Room id round-trips
        // even though the filename uses the timestamp.
        assertTrue(entry.content.contains("\"id\":5"))
        assertTrue(entry.content.contains("\"body\":\"Hello \\\"world\\\"\\nLine 2\""))
        assertTrue(entry.content.contains("\"attachments\":[\"attachments/a.wav\"]"))
    }

    @Test fun `append-to-single-file mode targets Wristotle slash notes slash notes dot md`() {
        // The append-mode file lives INSIDE the entity folder
        // (Wristotle/notes/notes.md) — not as a sibling — so every
        // note artefact (daily log + attachments/) stays grouped.
        val entry = renderer.render(
            note(),
            FileSyncFormatOptions(granularity = FileSyncGranularity.AppendToSingleFile),
        )
        assertEquals("Wristotle/notes/notes.md", entry.relativePath)
        assertEquals(WriteMode.Append, entry.mode)
    }

    @Test fun `note with empty body still renders without crashing`() {
        val entry = renderer.render(note(body = ""), FileSyncFormatOptions())
        assertTrue(entry.content.startsWith("---\n"))
        assertFalse(entry.content.contains("title:"))
    }
}
