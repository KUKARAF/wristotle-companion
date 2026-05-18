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
         * Schema is frozen at v1 — every column that exists on
         * [ConversationEntry] is part of the v1 definition. Migrations
         * from earlier dev-only v2 / v3 schemas were collapsed away
         * before any public install. [fallbackToDestructiveMigration]
         * is the belt-and-suspenders for any stray dev-build database
         * still on disk: Room will drop and recreate the table rather
         * than crash on "cannot find a migration."
         *
         * Once we start shipping to real users we treat this as the
         * locked baseline — any future column addition gets a proper
         * Migration registered here, never another destructive reset.
         */
        fun build(context: Context): ConversationDatabase =
            Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
