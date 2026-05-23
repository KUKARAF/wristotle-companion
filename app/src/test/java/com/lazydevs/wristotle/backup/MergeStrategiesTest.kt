package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.speech.nlu.bank.ExampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeStrategiesTest {

    // ── Notes ─────────────────────────────────────────────────────────────

    @Test fun notes_emptyExisting_insertsAll() {
        val incoming = listOf(
            note(id = 5, body = "a", at = 1),
            note(id = 6, body = "b", at = 2),
        )
        val out = MergeStrategies.mergeNotes(emptyList(), incoming)
        assertEquals(2, out.size)
        // Ids are zeroed for Room autogen.
        assertTrue(out.all { it.id == 0L })
        assertEquals(listOf("a", "b"), out.map { it.body })
    }

    @Test fun notes_dedupeByCreatedAtAndBody() {
        val existing = listOf(note(id = 1, body = "milk", at = 100))
        val incoming = listOf(
            note(id = 99, body = "milk", at = 100),  // duplicate — same (at, body)
            note(id = 100, body = "eggs", at = 100), // new — same at, different body
            note(id = 101, body = "milk", at = 200), // new — same body, different at
        )
        val out = MergeStrategies.mergeNotes(existing, incoming)
        assertEquals(listOf("eggs", "milk"), out.map { it.body })
    }

    @Test fun notes_dedupeWithinIncomingBatch() {
        val out = MergeStrategies.mergeNotes(
            existing = emptyList(),
            incoming = listOf(
                note(body = "x", at = 1),
                note(body = "x", at = 1),   // same key twice in one ZIP
            ),
        )
        assertEquals(1, out.size)
    }

    // ── Conversations ─────────────────────────────────────────────────────

    @Test fun conversations_dedupeByTimestampQueryResponse() {
        val existing = listOf(conv(at = 10, q = "call mom", r = "calling"))
        val incoming = listOf(
            conv(at = 10, q = "call mom", r = "calling"),    // duplicate
            conv(at = 10, q = "call mom", r = "different"),  // new — response differs
            conv(at = 11, q = "call mom", r = "calling"),    // new — timestamp differs
        )
        val out = MergeStrategies.mergeConversations(existing, incoming)
        assertEquals(2, out.size)
        assertTrue(out.all { it.id == 0L })
    }

    // ── NLU ───────────────────────────────────────────────────────────────

    @Test fun nlu_skipOnCollisionByNormalizedText() {
        val existing = listOf(example(norm = "call mom", usage = 10))
        val incoming = listOf(
            example(norm = "call mom", usage = 99),    // dedupe — local kept
            example(norm = "text dad", usage = 1),     // new
        )
        val out = MergeStrategies.mergeNluExamples(existing, incoming)
        assertEquals(1, out.size)
        assertEquals("text dad", out[0].normalizedText)
    }

    @Test fun nlu_reImportSameBackupIsIdempotent() {
        // Round-trip stability check: existing == incoming → nothing to insert.
        val sameSet = listOf(
            example(norm = "call mom", usage = 4),
            example(norm = "text dad", usage = 2),
        )
        val out = MergeStrategies.mergeNluExamples(existing = sameSet, incoming = sameSet)
        assertTrue(out.isEmpty())
    }

    // ── Aliases ───────────────────────────────────────────────────────────

    @Test fun aliases_backupWinsOnCollision() {
        val existing = mapOf("yt" to "old.youtube", "gmail" to "com.google.gmail")
        val incoming = mapOf("yt" to "new.youtube", "spot" to "com.spotify")
        val merged = MergeStrategies.mergeAliases(existing, incoming)
        assertEquals("new.youtube", merged["yt"])      // overwritten
        assertEquals("com.google.gmail", merged["gmail"]) // kept (local-only)
        assertEquals("com.spotify", merged["spot"])    // added
    }

    // ── Pins ──────────────────────────────────────────────────────────────

    @Test fun pins_dedupeByTitleAndTime() {
        val existing = listOf(pin("gym", 100L))
        val incoming = listOf(
            pin("gym", 100L),    // duplicate
            pin("gym", 200L),    // new — same title, different time
            pin("dentist", 100L), // new — same time, different title
        )
        val merged = MergeStrategies.mergePins(existing, incoming)
        // Existing stays first (newest-first ordering); new pins append.
        assertEquals(listOf("gym", "gym", "dentist"), merged.map { it.title })
        assertEquals(listOf(100L, 200L, 100L), merged.map { it.timeMs })
    }

    @Test fun pins_nullTimeKeyIsDistinctFromZero() {
        val merged = MergeStrategies.mergePins(
            existing = emptyList(),
            incoming = listOf(pin("a", null), pin("a", 0L), pin("a", null)),
        )
        // null and 0L are distinct keys — both kept once each.
        assertEquals(2, merged.size)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun note(id: Long = 0, body: String = "", at: Long = 0, audio: String? = null) =
        Note(id = id, body = body, createdAtEpochMs = at, source = "test", audioFilePath = audio)

    private fun conv(at: Long, q: String, r: String) = ConversationEntry(
        id = 0,
        timestampEpochMs = at,
        userQuery = q,
        responseText = r,
        handler = "test",
        requiresCompanion = true,
        success = true,
    )

    private fun example(norm: String, usage: Int) = ExampleEntry(
        id = 0,
        intent = "Call",
        rawText = norm,
        normalizedText = norm,
        source = "learned",
        addedAtEpochMs = 0,
        usageCount = usage,
    )

    private fun pin(title: String, timeMs: Long?) = ReminderRecord("id-$title-$timeMs", title, timeMs)
}
