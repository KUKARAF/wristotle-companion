package com.lazydevs.wristotle.speech.nlu.bank

import com.lazydevs.wristotle.speech.nlu.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plumbing tests for the learned-examples store. The DAO is faked with
 * an in-memory implementation that mirrors the unique-index dedup on
 * `normalizedText` (caught by [findByNormalized] before insert) — the
 * real Room dao uses that index + IGNORE-on-conflict at the SQL layer.
 *
 * Covers the rules the classifier ranking depends on:
 *  - normalization is canonical (lowercase, whitespace, trailing punct)
 *  - blank / oversize input is rejected at the boundary
 *  - per-intent cap is honoured so a learning loop can't unbound the bank
 *  - duplicates bump usageCount instead of inserting a second row
 */
class ExampleBankTest {

    // ── normalize ─────────────────────────────────────────────────────────

    @Test fun `normalize lowercases and trims`() {
        assertEquals("hello world", ExampleBank.normalize("  Hello World  "))
    }

    @Test fun `normalize collapses inner whitespace`() {
        assertEquals("a b c", ExampleBank.normalize("a   b\t\tc"))
    }

    @Test fun `normalize strips trailing sentence punctuation`() {
        assertEquals("call mom", ExampleBank.normalize("Call mom."))
        assertEquals("call mom", ExampleBank.normalize("Call mom!"))
        assertEquals("call mom", ExampleBank.normalize("Call mom?"))
        assertEquals("call mom", ExampleBank.normalize("Call mom,"))
        assertEquals("call mom", ExampleBank.normalize("Call mom;"))
        assertEquals("call mom", ExampleBank.normalize("Call mom:"))
    }

    @Test fun `normalize does not strip inner punctuation`() {
        // The user might dictate "it's me" — the apostrophe is part of
        // the canonical form, not noise to strip.
        assertEquals("it's me", ExampleBank.normalize("It's me"))
    }

    // ── addLearned: insert path ───────────────────────────────────────────

    @Test fun `addLearned inserts a new phrase and reports true`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)

        val inserted = bank.addLearned("Remind me at five", Intent.Reminder)

        assertTrue(inserted)
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals(Intent.Reminder.name, row.intent)
        assertEquals("Remind me at five", row.rawText)
        assertEquals("remind me at five", row.normalizedText)
        assertEquals("learned", row.source)
    }

    // ── addLearned: reject paths ──────────────────────────────────────────

    @Test fun `addLearned rejects blank input`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)

        assertFalse(bank.addLearned("", Intent.Call))
        assertFalse(bank.addLearned("   ", Intent.Call))
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun `addLearned rejects over-length input`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)
        val tooLong = "x".repeat(ExampleBank.MAX_LEARNED_LEN + 1)

        assertFalse(bank.addLearned(tooLong, Intent.Note))
        assertTrue(dao.rows.isEmpty())
    }

    // ── addLearned: duplicate bumps usageCount ────────────────────────────

    @Test fun `addLearned on duplicate normalized text updates usageCount`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)

        // Insert "Remind me at five" then re-insert with different casing.
        // The normalized form matches → no new row, existing usageCount + 1.
        bank.addLearned("Remind me at five", Intent.Reminder)
        val secondInsert = bank.addLearned("REMIND ME AT FIVE", Intent.Reminder)

        assertFalse(secondInsert)
        assertEquals(1, dao.rows.size)
        assertEquals(2, dao.rows.single().usageCount)
    }

    // ── addLearned: per-intent cap ────────────────────────────────────────

    @Test fun `addLearned skips when per-intent cap reached`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)
        repeat(ExampleBank.MAX_LEARNED_PER_INTENT) { i ->
            bank.addLearned("phrase number $i", Intent.Reminder)
        }
        assertEquals(ExampleBank.MAX_LEARNED_PER_INTENT, dao.rows.size)

        val accepted = bank.addLearned("one more phrase past the cap", Intent.Reminder)

        assertFalse(accepted)
        assertEquals(ExampleBank.MAX_LEARNED_PER_INTENT, dao.rows.size)
    }

    @Test fun `cap is per-intent so a different intent still accepts`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)
        repeat(ExampleBank.MAX_LEARNED_PER_INTENT) { i ->
            bank.addLearned("reminder phrase $i", Intent.Reminder)
        }

        val accepted = bank.addLearned("call mom", Intent.Call)

        assertTrue(accepted)
        assertEquals(ExampleBank.MAX_LEARNED_PER_INTENT + 1, dao.rows.size)
    }

    // ── delete ─────────────────────────────────────────────────────────

    @Test fun `deleteLearnedById removes only that row`() = runBlocking {
        val dao = FakeExampleDao()
        val bank = ExampleBank(dao)
        bank.addLearned("call mom", Intent.Call)
        bank.addLearned("call dad", Intent.Call)
        val targetId = dao.rows.first().id

        val removed = bank.deleteLearnedById(targetId)

        assertTrue(removed)
        assertEquals(1, dao.rows.size)
        assertNull(dao.rows.firstOrNull { it.id == targetId })
    }
}

/**
 * In-memory ExampleDao that mimics the production Room behaviour around
 * the unique `normalizedText` index. Not a Room generator — purpose-built
 * for these tests so the rules under test stay legible.
 */
private class FakeExampleDao : ExampleDao {
    val rows: MutableList<ExampleEntry> = mutableListOf()
    private var nextId: Long = 1

    override suspend fun learned(): List<ExampleEntry> =
        rows.filter { it.source == "learned" }

    override suspend fun learnedForIntent(intent: String): List<ExampleEntry> =
        rows.filter { it.source == "learned" && it.intent == intent }

    override suspend fun findByNormalized(norm: String): ExampleEntry? =
        rows.firstOrNull { it.normalizedText == norm }

    override suspend fun insert(entry: ExampleEntry): Long {
        // Mirror IGNORE-on-conflict: if normalizedText already exists, return 0.
        if (rows.any { it.normalizedText == entry.normalizedText }) return 0L
        val withId = entry.copy(id = nextId++)
        rows += withId
        return withId.id
    }

    override suspend fun update(entry: ExampleEntry) {
        val idx = rows.indexOfFirst { it.id == entry.id }
        if (idx >= 0) rows[idx] = entry
    }

    override suspend fun deleteLearned() {
        rows.removeAll { it.source == "learned" }
    }

    override suspend fun deleteLearnedById(id: Long): Int {
        val removed = rows.removeAll { it.id == id && it.source == "learned" }
        return if (removed) 1 else 0
    }

    override suspend fun countLearnedForIntent(intent: String): Int =
        rows.count { it.source == "learned" && it.intent == intent }

    override suspend fun countLearned(): Int =
        rows.count { it.source == "learned" }
}
