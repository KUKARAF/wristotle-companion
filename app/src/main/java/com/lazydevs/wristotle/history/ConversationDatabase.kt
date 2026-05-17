package com.lazydevs.wristotle.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val DB_NAME = "wristotle-conversation.db"

        // Single instance per process — built lazily by WristotleApplication.
        fun build(context: Context): ConversationDatabase =
            Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * v1 → v2: add nullable NLU shadow-mode columns. Existing rows get NULL
         * for both, which the UI renders as "no prediction" — same as today.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation_entries ADD COLUMN nluIntent TEXT")
                db.execSQL("ALTER TABLE conversation_entries ADD COLUMN nluConfidence REAL")
            }
        }
    }
}
