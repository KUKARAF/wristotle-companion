package com.lazydevs.wristotle.apps

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One launchable app on the device, captured by [AppIndexer.refresh].
 *
 * `normalizedLabel` is the lookup key: lowercased + whitespace-collapsed
 * + punctuation-stripped form of [label]. Stored on the row so the
 * matcher can run a single SQL contains-query instead of normalizing
 * every row on each lookup.
 *
 * The package id is the primary key — re-scanning is an REPLACE so
 * label changes (rare) are picked up cleanly without manual diffing.
 */
@Entity(
    tableName = "installed_apps",
    indices = [Index(value = ["normalizedLabel"])],
)
data class InstalledApp(
    @PrimaryKey val packageId: String,
    val label: String,
    val normalizedLabel: String,
    val lastScannedAtMs: Long,
)
