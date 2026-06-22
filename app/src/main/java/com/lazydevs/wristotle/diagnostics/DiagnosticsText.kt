// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.diagnostics

/**
 * Pure text helpers for the diagnostics bundle, extracted from
 * [DiagnosticsBuilder] so the privacy-relevant redaction is unit-testable.
 */
internal object DiagnosticsText {

    /** Runs of 7+ consecutive digits (phone numbers) in a log line. Deliberately
     *  light-touch — it does NOT catch separator-broken numbers ("555-123-4567"),
     *  by design: the conversation table redacts queries fully when the toggle is
     *  on, and over-redacting every short digit run would gut the logs' value. */
    private val DIGIT_RUN = Regex("\\b\\d{7,}\\b")

    /** Light-touch redaction for log lines — replaces a run of 7+ digits with
     *  `<digits>`. See [DIGIT_RUN] for the (intentional) limits. */
    fun redactDigits(text: String): String = DIGIT_RUN.replace(text, "<digits>")

    /** Escape a value for a single Markdown table cell: backslash/pipe escaped,
     *  newlines flattened to spaces, capped at 80 chars. */
    fun escapeCell(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").take(80)
}
