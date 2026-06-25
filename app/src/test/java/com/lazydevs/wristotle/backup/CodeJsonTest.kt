// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.codes.CodeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Saved-codes backup: the per-entity codec must round-trip (a dropped field is a
 * silent data-loss bug — see feedback_backup_roundtrip_test_mandatory), and the
 * merge must dedupe by (format, data) so restoring the same backup twice doesn't
 * duplicate cards.
 */
class CodeJsonTest {

    private fun code(id: String, format: String, data: String, alias: String = "") = CodeEntity(
        id = id, label = "Card", alias = alias, format = format, data = data, createdAtEpochMs = 1_700_000_000_000L,
    )

    @Test fun `code round-trips through the codec including the alias`() {
        val c = code("abc-123", "CODE_128", "123456789012", alias = "tesco")
        assertEquals(c, CodeJson.decode(CodeJson.encode(c), CodeJson.CURRENT_SCHEMA))
    }

    @Test fun `merge skips a code already present by format + data`() {
        val existing = listOf(code("id1", "CODE_128", "111"))
        val incoming = listOf(
            code("id1b", "CODE_128", "111"),   // dup of existing (same format+data) → skipped
            code("id2", "QR_CODE", "111"),      // same data, different format → kept
            code("id3", "CODE_128", "222"),     // new → kept
        )
        val merged = MergeStrategies.mergeCodes(existing, incoming)
        assertEquals(listOf("id2", "id3"), merged.map { it.id })
    }
}
