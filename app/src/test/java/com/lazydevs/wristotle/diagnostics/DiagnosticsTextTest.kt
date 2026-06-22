// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the diagnostics text helpers. [DiagnosticsText.redactDigits] is a
 * privacy primitive — under-redaction leaks phone numbers into a diagnostics
 * dump users paste into public issues — so its behaviour (and its INTENTIONAL
 * light-touch limits) are pinned here.
 */
class DiagnosticsTextTest {

    @Test fun redactsRunsOfSevenOrMoreDigits() {
        assertEquals("call <digits> now", DiagnosticsText.redactDigits("call 5551234567 now"))
        assertEquals("<digits>", DiagnosticsText.redactDigits("1234567"))
        assertEquals("<digits> and <digits>", DiagnosticsText.redactDigits("12345678 and 987654321"))
    }

    @Test fun leavesShortDigitRunsAlone() {
        // < 7 digits — timestamps, ports, short ids stay readable in logs.
        assertEquals("123456", DiagnosticsText.redactDigits("123456"))
        assertEquals("v1.13.0 code 11300", DiagnosticsText.redactDigits("v1.13.0 code 11300"))
    }

    @Test fun documentedLimit_separatorBrokenNumbersAreNotRedacted() {
        // Light-touch by design: groups < 7 digits each → not caught. The
        // conversation table is the full-PII guard; this only scrubs raw log
        // lines. If this ever needs tightening, change DIGIT_RUN + this test.
        assertEquals("555-123-4567", DiagnosticsText.redactDigits("555-123-4567"))
        // No word boundary around a letter-glued run → also not caught.
        assertEquals("ref1234567x", DiagnosticsText.redactDigits("ref1234567x"))
    }

    @Test fun escapeCellEscapesPipesBackslashesAndNewlines() {
        assertEquals("a\\|b", DiagnosticsText.escapeCell("a|b"))
        assertEquals("a\\\\b", DiagnosticsText.escapeCell("a\\b"))
        assertEquals("line1 line2", DiagnosticsText.escapeCell("line1\nline2"))
    }

    @Test fun escapeCellCapsAt80Chars() {
        assertEquals(80, DiagnosticsText.escapeCell("x".repeat(100)).length)
    }
}
