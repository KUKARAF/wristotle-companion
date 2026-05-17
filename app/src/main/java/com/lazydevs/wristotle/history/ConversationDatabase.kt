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

        // Single instance per process — built lazily by WristotleApplication.
        fun build(context: Context): ConversationDatabase =
            Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, DB_NAME)
                .build()
    }
}
