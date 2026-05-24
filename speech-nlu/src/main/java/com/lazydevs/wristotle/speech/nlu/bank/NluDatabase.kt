package com.lazydevs.wristotle.speech.nlu.bank

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ExampleEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class NluDatabase : RoomDatabase() {
    abstract fun exampleDao(): ExampleDao

    companion object {
        private const val DB_NAME = "wristotle-nlu.db"

        /**
         * v1 → v2: relabel `intent='Sms'` rows to `intent='SendMessage'`.
         *
         * Phase A2 of the messaging-apps feature subsumed Intent.Sms
         * into Intent.SendMessage. The Intent enum stays
         * forward-compatible (Intent.fromName falls back to Unknown on
         * a missing name) but learned phrases stored under "Sms" would
         * silently become Unknown — i.e. lost from the classifier's
         * vocabulary — without this migration. The unique index on
         * normalizedText is safe to keep: relabeling can't introduce
         * duplicate normalisations.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE nlu_examples SET intent = 'SendMessage' WHERE intent = 'Sms'")
            }
        }

        fun build(context: Context): NluDatabase =
            Room.databaseBuilder(context.applicationContext, NluDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
