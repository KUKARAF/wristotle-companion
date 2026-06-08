// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationPost::class],
    version = 1,
    exportSchema = false,
)
abstract class NotificationLogDatabase : RoomDatabase() {
    abstract fun notificationPostDao(): NotificationPostDao

    companion object {
        private const val DB_NAME = "wristotle-notif-log.db"

        /**
         * Built once per process by `WristotleApplication` (lazy — most
         * users never enable the log, so cold start stays cheap).
         *
         * Schema is locked at v1 from first ship; the log holds only
         * observational metadata so any future field addition gets a
         * proper Migration rather than a destructive reset. A reset
         * mid-day would lose the user's morning brief signal.
         */
        fun build(context: Context): NotificationLogDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NotificationLogDatabase::class.java,
                DB_NAME,
            ).build()
    }
}
