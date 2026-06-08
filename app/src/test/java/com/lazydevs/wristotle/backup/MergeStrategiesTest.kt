// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.speech.nlu.backup.*

import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.mcp.McpServerEntity
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.phone.ContactRef
import com.lazydevs.wristotle.nlu.learning.ExampleEntry
import com.lazydevs.wristotle.tasks.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ── Contact aliases + relink ─────────────────────────────────────────

    @Test fun contactAliases_backupWinsOnCollision() {
        val existing = mapOf(
            "mom" to ref("local-key-1"),
            "boss" to ref("local-key-2"),
        )
        val incoming = mapOf(
            "mom" to ref("backup-key-1"),
            "dad" to ref("backup-key-3"),
        )
        val merged = MergeStrategies.mergeContactAliases(existing, incoming)
        assertEquals("backup-key-1", merged["mom"]?.lookupKey)  // overwritten
        assertEquals("local-key-2", merged["boss"]?.lookupKey)  // kept
        assertEquals("backup-key-3", merged["dad"]?.lookupKey)  // added
    }

    @Test fun relink_keepsRefWhenLookupKeyStillValid() {
        // The "import on the same device" case — Contacts DB is
        // untouched, lookup keys still resolve, no relink needed.
        val original = ref("still-valid")
        val out = MergeStrategies.relinkContactRef(
            ref = original,
            isCurrent = { it == "still-valid" },
            byName = { error("should not be called when key is valid") },
            byNumber = { error("should not be called when key is valid") },
        )
        assertEquals(original, out)
    }

    @Test fun relink_findsByNameWhenKeyIsDead() {
        val original = ref("dead-key").copy(nameSnapshot = "Aparna")
        val out = MergeStrategies.relinkContactRef(
            ref = original,
            isCurrent = { false },                 // key is dead
            byName = { if (it == "Aparna") "fresh-key" else null },
            byNumber = { error("name match short-circuits before number") },
        )
        assertEquals("fresh-key", out.lookupKey)
        // Snapshots are kept — they're still the record of what the
        // alias was created against, useful if the new key later dies too.
        assertEquals(original.nameSnapshot, out.nameSnapshot)
        assertEquals(original.numberSnapshot, out.numberSnapshot)
    }

    @Test fun relink_fallsBackToNumberWhenNameMisses() {
        // User renamed the contact in Contacts after the alias was
        // created (export). Restore on a fresh device finds the number
        // still belongs to that person, even though the snapshot name
        // no longer matches.
        val original = ref("dead-key").copy(numberSnapshot = "+15551112222")
        val out = MergeStrategies.relinkContactRef(
            ref = original,
            isCurrent = { false },
            byName = { null },                     // name no longer matches
            byNumber = { if (it == "+15551112222") "fresh-key" else null },
        )
        assertEquals("fresh-key", out.lookupKey)
    }

    @Test fun relink_returnsOriginalRefWhenEverythingMisses() {
        // Contact was deleted from Contacts; relink can't recover.
        // Caller is expected to render the Settings card row as
        // "(not found)" and let the user prune.
        val original = ref("dead-key")
        val out = MergeStrategies.relinkContactRef(
            ref = original,
            isCurrent = { false },
            byName = { null },
            byNumber = { null },
        )
        assertEquals(original, out)
        assertEquals("dead-key", out.lookupKey)  // unchanged
    }

    private fun ref(lookupKey: String) = ContactRef(
        lookupKey = lookupKey,
        nameSnapshot = "Test Name",
        numberSnapshot = "+15550000000",
    )

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

    private fun task(
        id: Long = 0,
        text: String = "",
        at: Long = 0,
        completed: Boolean = false,
        completedAt: Long? = null,
    ) = TaskEntity(
        id = id,
        text = text,
        completed = completed,
        createdAtEpochMs = at,
        completedAtEpochMs = completedAt,
        source = "test",
    )

    // ── Tasks ─────────────────────────────────────────────────────────────

    @Test fun tasks_emptyExisting_insertsAll() {
        val incoming = listOf(
            task(id = 5, text = "buy milk", at = 1),
            task(id = 6, text = "call dentist", at = 2),
        )
        val out = MergeStrategies.mergeTasks(emptyList(), incoming)
        assertEquals(2, out.size)
        assertTrue("ids zeroed for Room autogen", out.all { it.id == 0L })
        assertEquals(listOf("buy milk", "call dentist"), out.map { it.text })
    }

    @Test fun tasks_dedupeByCreatedAtAndText() {
        val existing = listOf(task(id = 1, text = "buy milk", at = 100))
        val incoming = listOf(
            task(id = 99,  text = "buy milk",     at = 100),
            task(id = 100, text = "call dentist", at = 100),
            task(id = 101, text = "buy milk",     at = 200),
        )
        val out = MergeStrategies.mergeTasks(existing, incoming)
        assertEquals(listOf("call dentist", "buy milk"), out.map { it.text })
    }

    @Test fun tasks_preservesCompletedState() {
        val out = MergeStrategies.mergeTasks(
            existing = emptyList(),
            incoming = listOf(
                task(text = "buy milk", at = 1, completed = true, completedAt = 50),
                task(text = "call dentist", at = 2, completed = false),
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out[0].completed)
        assertEquals(50L, out[0].completedAtEpochMs)
        assertTrue(!out[1].completed)
    }

    @Test fun tasks_dedupeWithinIncomingBatch() {
        val out = MergeStrategies.mergeTasks(
            existing = emptyList(),
            incoming = listOf(
                task(text = "x", at = 1),
                task(text = "x", at = 1),
            ),
        )
        assertEquals(1, out.size)
    }

    // ── MCP servers ───────────────────────────────────────────────────────

    @Test fun mcpServers_dedupeByNameAndUrl() {
        val existing = listOf(
            mcp(id = 1, name = "github", url = "https://api.github.com/mcp"),
        )
        val incoming = listOf(
            // Same (name, url) → skip even though the auth header differs.
            mcp(id = 99, name = "github", url = "https://api.github.com/mcp", auth = "Bearer different"),
            // Same name, different url → distinct server.
            mcp(id = 100, name = "github", url = "https://other.com/mcp"),
            // Different name, same url → distinct server.
            mcp(id = 101, name = "github-mirror", url = "https://api.github.com/mcp"),
        )
        val out = MergeStrategies.mergeMcpServers(existing, incoming, stripAuthHeaders = false)
        assertEquals(2, out.size)
        assertTrue("ids zeroed for Room autogen", out.all { it.id == 0L })
        assertTrue(out.any { it.url == "https://other.com/mcp" })
        assertTrue(out.any { it.name == "github-mirror" })
    }

    @Test fun mcpServers_stripAuthHeadersBlanksOnInsert() {
        val incoming = listOf(mcp(name = "x", url = "https://x", auth = "Bearer sk-leak"))
        val out = MergeStrategies.mergeMcpServers(emptyList(), incoming, stripAuthHeaders = true)
        assertEquals(1, out.size)
        assertNull("secret stripped per restore-side opt-out", out[0].authHeader)
    }

    @Test fun mcpServers_preserveAuthHeadersWhenAllowed() {
        val incoming = listOf(mcp(name = "x", url = "https://x", auth = "Bearer sk-keep"))
        val out = MergeStrategies.mergeMcpServers(emptyList(), incoming, stripAuthHeaders = false)
        assertEquals("Bearer sk-keep", out[0].authHeader)
    }

    private fun mcp(
        id: Long = 0,
        name: String,
        url: String,
        auth: String? = null,
    ) = McpServerEntity(
        id = id,
        name = name,
        url = url,
        streamable = true,
        authHeader = auth,
        enabled = true,
    )
}