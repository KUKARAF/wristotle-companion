// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CodeEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CodeDatabase : RoomDatabase() {
    abstract fun codeDao(): CodeDao

    companion object {
        private const val DB_NAME = "wristotle-codes.db"

        /**
         * Built once per process by `WristotleApplication`. Schema locked at v1
         * from the first release — saved codes are user data, so any future
         * column gets a real Migration, never a destructive reset.
         */
        fun build(context: Context): CodeDatabase =
            Room.databaseBuilder(context.applicationContext, CodeDatabase::class.java, DB_NAME)
                .build()
    }
}
