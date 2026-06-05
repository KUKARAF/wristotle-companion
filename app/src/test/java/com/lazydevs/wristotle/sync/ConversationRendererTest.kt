package com.lazydevs.wristotle.sync

import com.lazydevs.wristotle.history.ConversationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRendererTest {

    private val renderer = ConversationRenderer()

    private fun entry(
        id: Long = 1,
        timestampEpochMs: Long = 1_700_000_000_000L,
        userQuery: String = "call mom",
        responseText: String = "Calling Mom…",
        handler: String = "call",
        success: Boolean = true,
        audio: String? = null,
        intent: String? = null,
        confidence: Float? = null,
    ) = ConversationEntry(
        id = id,
        timestampEpochMs = timestampEpochMs,
        userQuery = userQuery,
        responseText = responseText,
        handler = handler,
        requiresCompanion = true,
        success = success,
        nluIntent = intent,
        nluConfidence = confidence,
        audioFilePath = audio,
    )

    @Test fun `markdown one-file-per-entity writes to Wristotle slash conversations slash ISO timestamp`() {
        val out = renderer.render(entry(id = 42), FileSyncFormatOptions())
        assertEquals("Wristotle/conversations/2023-11-14T22-13-20Z.md", out.relativePath)
        assertEquals(WriteMode.Overwrite, out.mode)
    }

    @Test fun `markdown frontmatter carries id timestamp handler and success`() {
        val out = renderer.render(entry(id = 7), FileSyncFormatOptions())
        assertTrue(out.content.contains("id: 7"))
        assertTrue(out.content.contains("timestamp: 2023-11-14T22:13:20Z"))
        assertTrue(out.content.contains("handler: call"))
        assertTrue(out.content.contains("success: true"))
    }

    @Test fun `markdown body uses Q and A markers`() {
        val out = renderer.render(entry(), FileSyncFormatOptions())
        assertTrue(out.content.contains("**Q:** call mom"))
        assertTrue(out.content.contains("**A:** Calling Mom…"))
    }

    @Test fun `markdown audio link uses CommonMark and points at attachments sub-directory`() {
        val out = renderer.render(
            entry(audio = "/data/data/.../audio/2026-06-04T17-30-21.wav"),
            FileSyncFormatOptions(),
        )
        assertTrue(out.content.contains("[2026-06-04T17-30-21.wav](attachments/2026-06-04T17-30-21.wav)"))
        assertFalse(out.content.contains("![["))
        assertEquals(1, out.attachments.size)
        assertEquals(
            "Wristotle/conversations/attachments/2026-06-04T17-30-21.wav",
            out.attachments[0].relativePath,
        )
    }

    @Test fun `markdown frontmatter omits intent and confidence when null`() {
        val out = renderer.render(entry(intent = null, confidence = null), FileSyncFormatOptions())
        assertFalse(out.content.contains("intent:"))
        assertFalse(out.content.contains("confidence:"))
    }

    @Test fun `markdown frontmatter includes intent and confidence when present`() {
        val out = renderer.render(
            entry(intent = "Call", confidence = 0.92f),
            FileSyncFormatOptions(),
        )
        assertTrue(out.content.contains("intent: Call"))
        assertTrue(out.content.contains("confidence: 0.92"))
    }

    @Test fun `plain text format omits frontmatter and uses bracket marker for audio`() {
        val out = renderer.render(
            entry(audio = "/x/audio/a.wav"),
            FileSyncFormatOptions(format = FileSyncFormat.PlainText),
        )
        assertEquals("Wristotle/conversations/2023-11-14T22-13-20Z.txt", out.relativePath)
        assertTrue(out.content.contains("Q: call mom"))
        assertTrue(out.content.contains("A: Calling Mom…"))
        assertTrue(out.content.contains("[Audio: a.wav]"))
        assertFalse(out.content.contains("---"))
    }

    @Test fun `json format produces parseable structured output`() {
        val out = renderer.render(
            entry(
                id = 5,
                userQuery = "Hello \"world\"\nLine 2",
                responseText = "Hi there",
                intent = "Call",
                confidence = 0.5f,
                audio = "/x/audio/a.wav",
            ),
            FileSyncFormatOptions(format = FileSyncFormat.Json),
        )
        assertEquals("Wristotle/conversations/2023-11-14T22-13-20Z.json", out.relativePath)
        assertTrue(out.content.contains("\"id\":5"))
        assertTrue(out.content.contains("\"handler\":\"call\""))
        assertTrue(out.content.contains("\"success\":true"))
        assertTrue(out.content.contains("\"intent\":\"Call\""))
        assertTrue(out.content.contains("\"confidence\":0.5"))
        assertTrue(out.content.contains("\"userQuery\":\"Hello \\\"world\\\"\\nLine 2\""))
        assertTrue(out.content.contains("\"attachments\":[\"attachments/a.wav\"]"))
    }

    @Test fun `append-to-single-file targets Wristotle slash conversations slash conversations dot md`() {
        val out = renderer.render(
            entry(),
            FileSyncFormatOptions(granularity = FileSyncGranularity.AppendToSingleFile),
        )
        assertEquals("Wristotle/conversations/conversations.md", out.relativePath)
        assertEquals(WriteMode.Append, out.mode)
    }

    @Test fun `entry without audio has empty attachments list`() {
        val out = renderer.render(entry(audio = null), FileSyncFormatOptions())
        assertEquals(0, out.attachments.size)
        assertFalse(out.content.contains("[Audio"))
    }

    @Test fun `markdown emits the daily-log heading line so concatenations are scannable`() {
        val out = renderer.render(entry(), FileSyncFormatOptions())
        assertTrue(out.content.contains("## 2023-11-14T22:13:20Z"))
    }
}
