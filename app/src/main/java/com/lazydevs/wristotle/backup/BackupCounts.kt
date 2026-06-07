// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

/**
 * Per-category row counts shown next to each checkbox in the backup
 * picker. Null fields are "no count to show" (e.g. for settings
 * categories that aren't enumerable — Weather settings is just on/off).
 *
 * Two consumers:
 *  - Export dialog: counts loaded fresh from live DAOs at dialog-open
 *    via [BackupExporter.countAll].
 *  - Restore preview: counts read from `manifest.stats` of the picked
 *    ZIP via [fromManifestStats].
 *
 * `audioBytes` is bundled with `audioRecordings` (count of WAVs); the
 * UI renders both as "Audio recordings (12 · 4.3 MB)" when bytes are
 * known.
 */
data class BackupCounts(
    val notes: Int? = null,
    val tasks: Int? = null,
    val conversations: Int? = null,
    val reminders: Int? = null,
    val nluLearned: Int? = null,
    val appAliases: Int? = null,
    val contactAliases: Int? = null,
    val audioRecordings: Int? = null,
    val audioBytes: Long? = null,
    val mcpServers: Int? = null,
) {
    companion object {
        /** Empty — placeholder before counts load. */
        val EMPTY = BackupCounts()

        /** Builds counts from a restore-side manifest. Settings-only
         *  categories stay null (no count exists). Audio bytes aren't in
         *  the manifest yet — pass null. */
        fun fromManifestStats(stats: BackupManifest.Stats): BackupCounts = BackupCounts(
            notes = stats.notes,
            tasks = stats.tasks,
            conversations = stats.conversations,
            reminders = stats.reminders,
            nluLearned = stats.nluLearned,
            appAliases = stats.aliases,
            contactAliases = stats.contactAliases,
            mcpServers = stats.mcpServers,
            // audioRecordings + audioBytes: not in manifest stats today; the
            // restore preview shows the row without a count.
        )
    }
}