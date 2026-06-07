// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.apps

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One launchable app on the device, captured by [AppIndexer.refresh].
 *
 * Two indexed lookup keys, both normalised (lowercased + whitespace-
 * collapsed + punctuation-stripped):
 *
 *   - [normalizedLabel] — derived from [label], the user-visible name.
 *   - [normalizedPackage] — derived from the package id with vendor /
 *     TLD prefix and build-variant suffix segments stripped. Catches
 *     queries that match the package path but not the launcher label
 *     (e.g. dictating "youtube music" when the launcher label is
 *     "YT Music" but the package is `app.morphe.android.apps.youtube.music`).
 *
 * Both columns are searched by the AppIndex's four-tier matcher in
 * order; label hits are tried before package hits within each tier so
 * the user-visible name always wins when both columns match.
 *
 * The package id is the primary key — re-scanning is an REPLACE so
 * label / package changes (rare) are picked up cleanly without manual diffing.
 */
@Entity(
    tableName = "installed_apps",
    indices = [
        Index(value = ["normalizedLabel"]),
        Index(value = ["normalizedPackage"]),
    ],
)
data class InstalledApp(
    @PrimaryKey val packageId: String,
    val label: String,
    val normalizedLabel: String,
    val normalizedPackage: String,
    val lastScannedAtMs: Long,
)