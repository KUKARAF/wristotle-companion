package com.lazydevs.wristotle.apps

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Standalone Room database for the installed-app index. Separate from
 * the conversation-history and NLU databases so the schema can evolve
 * independently and a "Rescan apps" wipe never threatens user data.
 */
@Database(
    entities = [InstalledApp::class],
    version = 1,
    exportSchema = false,
)
abstract class AppIndexDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao

    companion object {
        private const val DB_NAME = "wristotle-app-index.db"

        fun build(context: Context): AppIndexDatabase =
            Room.databaseBuilder(context.applicationContext, AppIndexDatabase::class.java, DB_NAME)
                .build()
    }
}
