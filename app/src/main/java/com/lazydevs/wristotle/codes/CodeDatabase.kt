// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CodeEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class CodeDatabase : RoomDatabase() {
    abstract fun codeDao(): CodeDao

    companion object {
        private const val DB_NAME = "wristotle-codes.db"

        /** v2: add the [CodeEntity.alias] column. Saved codes are user data, so
         *  this is a real ALTER, never a destructive reset. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE codes ADD COLUMN alias TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Built once per process by `WristotleApplication`. */
        fun build(context: Context): CodeDatabase =
            Room.databaseBuilder(context.applicationContext, CodeDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
