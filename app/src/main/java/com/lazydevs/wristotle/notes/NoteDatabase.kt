// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Note::class],
    version = 1,
    exportSchema = false,
)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        private const val DB_NAME = "wristotle-notes.db"

        /**
         * Built once per process by `WristotleApplication`.
         *
         * Schema is locked at v1 from the first release — once notes are
         * shipped, any future column addition gets a proper Migration
         * registered here, never a destructive reset (notes are user data;
         * losing them across an upgrade is unacceptable).
         */
        fun build(context: Context): NoteDatabase =
            Room.databaseBuilder(context.applicationContext, NoteDatabase::class.java, DB_NAME)
                .build()
    }
}