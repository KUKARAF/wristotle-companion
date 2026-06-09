// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormat
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormatOptions
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncGranularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class FileSyncTypesTest {

    @Test fun `RenderedEntry defaults are Overwrite with no attachments`() {
        val entry = RenderedEntry(
            relativePath = "Wristotle/notes/abc.md",
            content = "# hi\n",
        )
        assertEquals(WriteMode.Overwrite, entry.mode)
        assertEquals(emptyList<Attachment>(), entry.attachments)
    }

    @Test fun `FileSyncFormatOptions defaults to Markdown + OneFilePerEntity`() {
        val opts = FileSyncFormatOptions()
        assertEquals(FileSyncFormat.Markdown, opts.format)
        assertEquals(FileSyncGranularity.OneFilePerEntity, opts.granularity)
    }

    // Lock down the enum names — they're persisted as strings in
    // SharedPreferences (FileSyncSettings) and the BackupManifest will
    // serialise them as-is. Any rename here means a migration on the
    // settings + a backup-decoder compat shim.
    @Test fun `enum names are stable and exhaustive`() {
        assertEquals(
            listOf("Markdown", "PlainText", "Json"),
            FileSyncFormat.entries.map { it.name },
        )
        assertEquals(
            listOf("OneFilePerEntity", "AppendToSingleFile"),
            FileSyncGranularity.entries.map { it.name },
        )
        assertEquals(listOf("Overwrite", "Append"), WriteMode.entries.map { it.name })
    }

    // A renderer is just a pure function over (T, options) → entry.
    // Demonstrate the contract with a trivial String renderer so the
    // interface stays exercised even before NoteRenderer lands.
    @Test fun `renderer interface is a pure function`() {
        val renderer = object : FileSyncRenderer<String> {
            override fun render(entity: String, options: FileSyncFormatOptions) = RenderedEntry(
                relativePath = "test/${entity.hashCode()}.md",
                content = "# $entity\n",
            )
        }
        val out = renderer.render("hello", FileSyncFormatOptions())
        assertEquals("# hello\n", out.content)
        assertEquals(WriteMode.Overwrite, out.mode)
    }

    @Test fun `Attachment carries source + target side-by-side`() {
        val source = File("/tmp/x.wav")
        val a = Attachment(sourceFile = source, relativePath = "Wristotle/notes/attachments/x.wav")
        assertSame(source, a.sourceFile)
        assertEquals("Wristotle/notes/attachments/x.wav", a.relativePath)
    }
}