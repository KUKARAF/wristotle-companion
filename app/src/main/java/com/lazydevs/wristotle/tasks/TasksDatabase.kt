// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.tasks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TasksDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        private const val DB_NAME = "wristotle-tasks.db"

        /**
         * Built once per process by `WristotleApplication`.
         *
         * Schema is locked at v1 from first release — tasks are user
         * data, so any future column addition gets a proper Migration
         * registered here, never a destructive reset. Mirrors the
         * non-destructive contract NoteDatabase follows.
         */
        fun build(context: Context): TasksDatabase =
            Room.databaseBuilder(context.applicationContext, TasksDatabase::class.java, DB_NAME)
                .build()
    }
}