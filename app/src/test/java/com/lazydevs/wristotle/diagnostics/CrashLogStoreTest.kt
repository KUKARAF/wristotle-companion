// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `write produces a file containing the stack trace`() {
        val dir = File(tmp.root, "crashes").apply { mkdirs() }
        val ex = IllegalStateException("synthetic — for test")
        CrashLogStore.write(dir, Thread.currentThread(), ex)

        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        val body = files[0].readText()
        assertTrue("missing throwable name", body.contains("IllegalStateException"))
        assertTrue("missing message", body.contains("synthetic — for test"))
        assertTrue("missing trace frame", body.contains(this::class.java.simpleName))
    }

    @Test fun `recent returns newest first`() {
        val dir = File(tmp.root, "crashes").apply { mkdirs() }
        // Epoch is in the filename — older files have smaller numbers,
        // so the sort key is implicit and deterministic.
        File(dir, "crash-1000.txt").writeText("older")
        File(dir, "crash-2000.txt").writeText("newer")
        File(dir, "crash-3000.txt").writeText("newest")

        val files = CrashLogStore.recent(tmp.root, limit = 10)
        assertEquals(listOf("crash-3000.txt", "crash-2000.txt", "crash-1000.txt"),
            files.map { it.name })
    }

    @Test fun `prune keeps only the latest five files`() {
        val dir = File(tmp.root, "crashes").apply { mkdirs() }
        // Pre-seed 4 old files, then write a real one with a known
        // later timestamp — total = 5, the cap, no prune yet.
        // Using explicit `nowEpochMs` avoids any collision when two
        // back-to-back writes land in the same millisecond.
        repeat(4) { File(dir, "crash-${1000 + it}.txt").writeText("old") }
        CrashLogStore.write(dir, Thread.currentThread(), RuntimeException("boom"), nowEpochMs = 2000)
        assertEquals(5, dir.listFiles()!!.size)

        // Sixth crash → oldest must be evicted.
        CrashLogStore.write(dir, Thread.currentThread(), RuntimeException("again"), nowEpochMs = 3000)
        val remaining = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(5, remaining.size)
        assertFalse("oldest file should be gone", remaining.contains("crash-1000.txt"))
        assertEquals(
            listOf("crash-1001.txt", "crash-1002.txt", "crash-1003.txt", "crash-2000.txt", "crash-3000.txt"),
            remaining,
        )
    }

    @Test fun `crash file is scrubbed of secret-shaped values`() {
        // Belt-and-braces: even if a third-party stack trace embeds a key
        // in its message, the file on disk must not carry it. The full
        // pattern audit lives in SensitiveScrubTest — this just confirms
        // the wiring at the CrashLogStore boundary.
        val dir = File(tmp.root, "crashes").apply { mkdirs() }
        val ex = RuntimeException("HTTP 401 hitting https://api.example.com/v1/x?api_key=hunter2hunter2")
        CrashLogStore.write(dir, Thread.currentThread(), ex, nowEpochMs = 4000)

        val body = File(dir, "crash-4000.txt").readText()
        assertFalse("raw key leaked into crash file: $body", body.contains("hunter2"))
        assertTrue("redaction marker missing", body.contains("<redacted>"))
    }

    @Test fun `clear wipes the directory`() {
        val dir = File(tmp.root, "crashes").apply { mkdirs() }
        File(dir, "crash-1.txt").writeText("x")
        File(dir, "crash-2.txt").writeText("y")

        CrashLogStore.clear(tmp.root)
        assertEquals(0, dir.listFiles()!!.size)
    }
}
