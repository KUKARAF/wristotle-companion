package com.lazydevs.wristotle.speech.nlu.bank

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ExampleEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class NluDatabase : RoomDatabase() {
    abstract fun exampleDao(): ExampleDao

    companion object {
        private const val DB_NAME = "wristotle-nlu.db"

        fun build(context: Context): NluDatabase =
            Room.databaseBuilder(context.applicationContext, NluDatabase::class.java, DB_NAME)
                .build()
    }
}
