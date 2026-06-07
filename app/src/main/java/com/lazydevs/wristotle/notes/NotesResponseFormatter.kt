// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import com.lazydevs.wristotle.transport.MessageKeys

/**
 * Formats the most-recent notes into the single CSTRING payload the watch
 * receives over `NOTES_RESPONSE`. Each note is the body with newlines
 * collapsed to single spaces (the watch renders one line per row in its
 * MenuLayer, so embedded newlines would split awkwardly), truncated to
 * [MAX_BODY_CHARS]. Notes are joined by [MessageKeys.NOTES_SEPARATOR].
 *
 * The total output is hard-capped at [MAX_TOTAL_CHARS] to stay well within
 * Pebble's AppMessage inbox/outbox budget (~636-byte ceiling; ~14 bytes
 * framing overhead per tuple).
 *
 * Pure function — unit-testable without Android.
 */
object NotesResponseFormatter {
    const val MAX_NOTES: Int = 6
    const val MAX_BODY_CHARS: Int = 120
    const val MAX_TOTAL_CHARS: Int = 580

    fun format(notes: List<Note>): String {
        if (notes.isEmpty()) return ""
        val out = StringBuilder()
        for (note in notes.take(MAX_NOTES)) {
            val sanitized = note.body.replace('\n', ' ').replace('\r', ' ').trim()
            if (sanitized.isEmpty()) continue
            val truncated = if (sanitized.length > MAX_BODY_CHARS) {
                sanitized.substring(0, MAX_BODY_CHARS - 1) + ELLIPSIS
            } else sanitized
            // Gate on "already wrote something" rather than the input index —
            // a blank note that gets skipped above must not leave a stray
            // leading separator on the next valid note.
            val needsSep = out.isNotEmpty()
            val projected = out.length + truncated.length + if (needsSep) 1 else 0
            if (projected > MAX_TOTAL_CHARS) break
            if (needsSep) out.append(MessageKeys.NOTES_SEPARATOR)
            out.append(truncated)
        }
        return out.toString()
    }

    private const val ELLIPSIS = '…'
}