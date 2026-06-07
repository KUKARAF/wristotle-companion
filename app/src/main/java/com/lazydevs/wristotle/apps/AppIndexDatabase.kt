// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.apps

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Standalone Room database for the installed-app index. Separate from
 * the conversation-history and NLU databases so the schema can evolve
 * independently and a "Rescan apps" wipe never threatens user data.
 */
@Database(
    entities = [InstalledApp::class],
    version = 2,
    exportSchema = false,
)
abstract class AppIndexDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao

    companion object {
        private const val DB_NAME = "wristotle-app-index.db"

        fun build(context: Context): AppIndexDatabase =
            Room.databaseBuilder(context.applicationContext, AppIndexDatabase::class.java, DB_NAME)
                // v1→v2 added the normalizedPackage column. The table is a
                // regenerable cache populated by Settings → Scan installed
                // apps, so a destructive drop is cheap — the user just
                // re-taps Scan once and we repopulate with both columns.
                // Same migration policy as the conversation-history DB.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}