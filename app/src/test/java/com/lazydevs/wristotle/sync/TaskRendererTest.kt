// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormat
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormatOptions
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncGranularity
import com.lazydevs.wristotle.tasks.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRendererTest {

    private val renderer = TaskRenderer()

    private fun task(
        id: Long = 1,
        text: String = "buy milk",
        completed: Boolean = false,
        createdAtEpochMs: Long = 1_700_000_000_000L,
        completedAtEpochMs: Long? = null,
        source: String = "watch",
    ) = TaskEntity(id, text, completed, createdAtEpochMs, completedAtEpochMs, source)

    private fun opts(
        format: FileSyncFormat = FileSyncFormat.Markdown,
        granularity: FileSyncGranularity = FileSyncGranularity.AppendToSingleFile,
    ) = FileSyncFormatOptions(format, granularity)

    @Test fun `single-file markdown renders a pending checkbox line`() {
        val r = renderer.render(task(text = "buy milk"), opts())
        assertEquals("Wristotle/tasks/tasks.md", r.relativePath)
        assertEquals(WriteMode.Append, r.mode)
        assertEquals("- [ ] buy milk", r.content)
    }

    @Test fun `completed task renders a checked box`() {
        val r = renderer.render(task(text = "call dentist", completed = true), opts())
        assertEquals("- [x] call dentist", r.content)
    }

    @Test fun `one-file-per-task writes a per-id markdown doc with frontmatter`() {
        val r = renderer.render(
            task(id = 7, text = "ship it", completed = true, completedAtEpochMs = 1_700_000_100_000L),
            opts(granularity = FileSyncGranularity.OneFilePerEntity),
        )
        assertEquals("Wristotle/tasks/7.md", r.relativePath)
        assertEquals(WriteMode.Overwrite, r.mode)
        assertTrue(r.content.contains("status: done"))
        assertTrue(r.content.contains("completed_at:"))
        assertTrue(r.content.contains("- [x] ship it"))
    }

    @Test fun `plaintext line`() {
        val r = renderer.render(task(text = "water plants"), opts(format = FileSyncFormat.PlainText))
        assertEquals("Wristotle/tasks/tasks.txt", r.relativePath)
        assertEquals("[ ] water plants", r.content)
    }

    @Test fun `json has fields and escapes`() {
        val r = renderer.render(
            task(text = "say \"hi\"", completed = false),
            opts(format = FileSyncFormat.Json),
        )
        assertEquals("Wristotle/tasks/tasks.json", r.relativePath)
        assertTrue(r.content.contains("\"completed\":false"))
        assertTrue(r.content.contains("\\\"hi\\\""))
        assertTrue(r.content.contains("\"completedAt\":null"))
    }

    @Test fun `no attachments for tasks`() {
        assertTrue(renderer.render(task(), opts()).attachments.isEmpty())
    }
}
