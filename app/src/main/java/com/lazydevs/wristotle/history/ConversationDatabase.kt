// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val DB_NAME = "wristotle-conversation.db"

        /**
         * Built once per process by `WristotleApplication`.
         *
         * Schema is frozen at v1 — every column on [ConversationEntry] is part
         * of the v1 definition, and the app now ships to real users, so this is
         * the locked baseline. Any future column addition MUST register a proper
         * Migration here.
         *
         * We deliberately do NOT use `fallbackToDestructiveMigration`: dropping
         * the table would silently wipe the user's conversation history on a
         * version mismatch. Without a registered migration Room throws instead —
         * a loud failure that forces us to ship the migration rather than lose
         * user data. (Earlier dev-only v2/v3 schemas predate any public install,
         * so no real user's DB needs the destructive escape hatch.)
         */
        fun build(context: Context): ConversationDatabase =
            Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, DB_NAME)
                .build()
    }
}