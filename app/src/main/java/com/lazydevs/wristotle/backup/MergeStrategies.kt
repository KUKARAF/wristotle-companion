// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.handlers.ReminderRecord
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.mcp.McpServerEntity
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.phone.ContactRef
import com.lazydevs.wristotle.speech.nlu.bank.ExampleEntry
import com.lazydevs.wristotle.tasks.TaskEntity

/**
 * Pure per-domain merge functions used by [BackupImporter]. Each takes the
 * existing local rows + the rows decoded from the backup and returns the
 * derived "what to actually insert / update / persist" set, with the
 * dedupe rule per [the backup plan].
 *
 * Pure on purpose: no Android Context, no Room, no IO. Easiest place to
 * unit-test the import edge cases (Phase D tests).
 */
object MergeStrategies {

    /** Notes to insert into the live DB. Skip when the incoming row matches an
     *  existing one on the dedupe key. Re-assign id-via-Room-autogen by
     *  zeroing the incoming row's id (Room's @Insert(autoGenerate) does the rest). */
    fun mergeNotes(existing: List<Note>, incoming: List<Note>): List<Note> {
        val seen = existing.map { noteKey(it.createdAtEpochMs, it.body) }.toMutableSet()
        return incoming.mapNotNull { row ->
            val key = noteKey(row.createdAtEpochMs, row.body)
            if (seen.add(key)) row.copy(id = 0) else null
        }
    }

    /** Tasks to insert. Dedupe by (createdAtEpochMs, text) — same shape as
     *  notes since both are user-typed/dictated free text with a creation
     *  timestamp. `completed` state from the backup is preserved so a
     *  restore of a fully-cleared checklist doesn't re-open all the tasks. */
    fun mergeTasks(existing: List<TaskEntity>, incoming: List<TaskEntity>): List<TaskEntity> {
        val seen = existing.map { taskKey(it.createdAtEpochMs, it.text) }.toMutableSet()
        return incoming.mapNotNull { row ->
            val key = taskKey(row.createdAtEpochMs, row.text)
            if (seen.add(key)) row.copy(id = 0) else null
        }
    }

    /** Conversation entries to insert. Dedupe by (timestamp, query, response). */
    fun mergeConversations(
        existing: List<ConversationEntry>,
        incoming: List<ConversationEntry>,
    ): List<ConversationEntry> {
        val seen = existing
            .map { conversationKey(it.timestampEpochMs, it.userQuery, it.responseText) }
            .toMutableSet()
        return incoming.mapNotNull { row ->
            val key = conversationKey(row.timestampEpochMs, row.userQuery, row.responseText)
            if (seen.add(key)) row.copy(id = 0) else null
        }
    }

    /**
     * NLU learned examples — the `normalizedText` column has a UNIQUE INDEX.
     * Skip on collision: a backup phrase that already exists locally leaves
     * the local row (and its usageCount) untouched. This keeps re-import
     * idempotent — restoring the same backup twice doesn't double-count any
     * usageCount — and matches the dedupe behaviour for notes / conversations.
     *
     * The trade-off: cross-device merges don't accumulate use across devices.
     * For a recogniser-tuning hint that's fine: each device's local usage is
     * what actually trained that device's model. A future "import overrides
     * local usage" toggle could revisit this.
     */
    fun mergeNluExamples(
        existing: List<ExampleEntry>,
        incoming: List<ExampleEntry>,
    ): List<ExampleEntry> {
        val seen = existing.map { it.normalizedText }.toMutableSet()
        return incoming.mapNotNull { row ->
            if (seen.add(row.normalizedText)) row.copy(id = 0) else null
        }
    }

    /** App aliases. Backup wins on key collision — restore intent overrides
     *  any local alias the user happened to set after the export. */
    fun mergeAliases(
        existing: Map<String, String>,
        incoming: Map<String, String>,
    ): Map<String, String> = existing + incoming

    /** Contact aliases. Backup wins on key collision — same rationale as
     *  [mergeAliases]. Relinking the lookup keys for the imported rows
     *  is a separate, IO-touching pass (see [relinkContactRef]); this
     *  merge stays pure so the policy is unit-testable. */
    fun mergeContactAliases(
        existing: Map<String, ContactRef>,
        incoming: Map<String, ContactRef>,
    ): Map<String, ContactRef> = existing + incoming

    /**
     * Restore-time relink for a single [ContactRef]. Android lookup keys
     * encode account-side identifiers that may not exist on a fresh
     * device (factory reset, contacts re-imported from VCF, local-only
     * Contacts that don't sync). The snapshot fields then carry the
     * weight: if the lookup key is dead, try to find the same human via
     * exact-name then phone-number on the new device's Contacts.
     *
     * Pure: callers inject the actual Contacts queries as lambdas
     * ([isCurrent] / [byName] / [byNumber]) so the policy is unit-tested
     * without a ContentResolver. The wrapper in [BackupImporter] supplies
     * the real provider queries.
     *
     * Returns either an updated [ContactRef] with the new lookup key (when
     * relink succeeds) or the original (when nothing matched — alias
     * becomes a dead link and the Settings card flags it).
     */
    fun relinkContactRef(
        ref: ContactRef,
        isCurrent: (lookupKey: String) -> Boolean,
        byName: (name: String) -> String?,
        byNumber: (number: String) -> String?,
    ): ContactRef {
        if (isCurrent(ref.lookupKey)) return ref
        byName(ref.nameSnapshot)?.let { return ref.copy(lookupKey = it) }
        byNumber(ref.numberSnapshot)?.let { return ref.copy(lookupKey = it) }
        return ref
    }

    /**
     * MCP servers to insert. Dedupe by (name, url) — same server is the
     * same server whether or not it was renamed locally. Existing rows
     * are preserved (the local auth_header isn't clobbered by a backup
     * row that lacks one). [stripAuthHeaders] sets each incoming row's
     * `authHeader` to null before the comparison so a "no secrets"
     * restore can't smuggle the field in via this path.
     */
    fun mergeMcpServers(
        existing: List<McpServerEntity>,
        incoming: List<McpServerEntity>,
        stripAuthHeaders: Boolean,
    ): List<McpServerEntity> {
        val sanitised = if (stripAuthHeaders) incoming.map { it.copy(authHeader = null) } else incoming
        val seen = HashSet<Pair<String, String>>(existing.size).apply {
            existing.forEach { add(it.name to it.url) }
        }
        return sanitised.mapNotNull { row ->
            if (seen.add(row.name to row.url)) row.copy(id = 0) else null
        }
    }

    /**
     * Reminder pins. Dedupe by (title, timeMs). Keep newest-first ordering of
     * existing first, then append unseen incoming. PinStore enforces a hard
     * cap of 10 — caller is responsible for the FIFO trim after the merge.
     */
    fun mergePins(
        existing: List<ReminderRecord>,
        incoming: List<ReminderRecord>,
    ): List<ReminderRecord> {
        val seen = existing.map { pinKey(it.title, it.timeMs) }.toMutableSet()
        val merged = existing.toMutableList()
        for (row in incoming) {
            if (seen.add(pinKey(row.title, row.timeMs))) merged += row
        }
        return merged
    }

    // ── Keys ──────────────────────────────────────────────────────────────
    // String triplets, not hashes — collisions would skip a legitimate row
    // silently. Use the natural identity directly; size is fine in practice
    // (a few hundred rows at import time).

    internal fun noteKey(createdAtEpochMs: Long, body: String): String =
        "$createdAtEpochMs$body"

    internal fun taskKey(createdAtEpochMs: Long, text: String): String =
        "$createdAtEpochMs$text"

    internal fun conversationKey(timestampMs: Long, query: String, response: String): String =
        "$timestampMs$query$response"

    internal fun pinKey(title: String, timeMs: Long?): String =
        "$title${timeMs ?: ""}"
}