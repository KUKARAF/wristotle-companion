// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.alarms

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Separate DB file (`wristotle-alarms.db`) — matches the per-feature
 * pattern used by Notes / Tasks / MCP / NLU. Schema 1; future
 * migrations land as named `Migration` objects rather than destructive
 * `fallbackToDestructiveMigration` so user-created alarms survive
 * upgrades.
 */
@Database(entities = [AlarmEntity::class], version = 1, exportSchema = false)
abstract class AlarmsDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    companion object {
        @Volatile private var INSTANCE: AlarmsDatabase? = null

        fun get(context: Context): AlarmsDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AlarmsDatabase::class.java,
                "wristotle-alarms.db",
            ).build().also { INSTANCE = it }
        }
    }
}