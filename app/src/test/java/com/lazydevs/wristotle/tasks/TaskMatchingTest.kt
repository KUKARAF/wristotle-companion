// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tasks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMatchingTest {

    private val pending = listOf(
        task(id = 1, text = "buy milk", created = 10),
        task(id = 2, text = "call the dentist", created = 20),
        task(id = 3, text = "buy bread", created = 30),
    )

    private fun search(needle: String): List<TaskEntity> =
        pending.filter { it.text.lowercase().contains(needle.lowercase()) }

    private val mostRecent: TaskEntity = pending.maxBy { it.createdAtEpochMs }

    @Test fun resolve_singleMatch() = runBlocking {
        val r = TaskMatching.resolve(
            target = "dentist",
            searchPending = ::search,
            mostRecentPending = { mostRecent },
        )
        assertTrue(r is TaskMatching.MatchResult.Single)
        assertEquals(2L, (r as TaskMatching.MatchResult.Single).task.id)
    }

    @Test fun resolve_ambiguous_returnsAllMatches() = runBlocking {
        val r = TaskMatching.resolve(
            target = "buy",
            searchPending = ::search,
            mostRecentPending = { mostRecent },
        )
        assertTrue(r is TaskMatching.MatchResult.Ambiguous)
        val matches = (r as TaskMatching.MatchResult.Ambiguous).matches
        assertEquals(2, matches.size)
        assertEquals(setOf("buy milk", "buy bread"), matches.map { it.text }.toSet())
    }

    @Test fun resolve_noneReturnsNone() = runBlocking {
        val r = TaskMatching.resolve(
            target = "laundry",
            searchPending = ::search,
            mostRecentPending = { mostRecent },
        )
        assertTrue(r is TaskMatching.MatchResult.None)
        assertEquals("laundry", (r as TaskMatching.MatchResult.None).searched)
    }

    @Test fun resolve_lastKeywordsBypassTextMatching() = runBlocking {
        for (kw in listOf("last", "latest", "most recent", "the last one")) {
            val r = TaskMatching.resolve(
                target = kw,
                searchPending = { error("should not search") },
                mostRecentPending = { mostRecent },
            )
            assertTrue("'$kw' → Single, got $r", r is TaskMatching.MatchResult.Single)
            assertEquals(mostRecent.id, (r as TaskMatching.MatchResult.Single).task.id)
        }
    }

    @Test fun resolve_lastKeywordWithEmptyPendingReturnsNoPendingTasks() = runBlocking {
        val r = TaskMatching.resolve(
            target = "last",
            searchPending = { error("should not search") },
            mostRecentPending = { null },
        )
        assertEquals(TaskMatching.MatchResult.NoPendingTasks, r)
    }

    @Test fun resolve_emptyTargetIsNone() = runBlocking {
        val r = TaskMatching.resolve(
            target = "  ",
            searchPending = { emptyList() },
            mostRecentPending = { mostRecent },
        )
        assertTrue(r is TaskMatching.MatchResult.None)
    }

    @Test fun renderAmbiguous_inlinesAllWhenSmall() {
        val list = pending.take(2)
        val s = TaskMatching.renderAmbiguous(list)
        assertEquals("Multiple matches: buy milk, call the dentist — which one?", s)
    }

    @Test fun renderAmbiguous_truncatesWithPlusNMoreSuffix() {
        val s = TaskMatching.renderAmbiguous(pending, maxInline = 2)
        assertEquals("Multiple matches: buy milk, call the dentist (+1 more) — which one?", s)
    }

    private fun task(id: Long, text: String, created: Long) = TaskEntity(
        id = id,
        text = text,
        completed = false,
        createdAtEpochMs = created,
        completedAtEpochMs = null,
        source = "test",
    )
}