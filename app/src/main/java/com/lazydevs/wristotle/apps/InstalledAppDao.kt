package com.lazydevs.wristotle.apps

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface InstalledAppDao {

    @Query("SELECT * FROM installed_apps ORDER BY label COLLATE NOCASE ASC")
    suspend fun all(): List<InstalledApp>

    @Query("SELECT COUNT(*) FROM installed_apps")
    suspend fun count(): Int

    @Query("SELECT MAX(lastScannedAtMs) FROM installed_apps")
    suspend fun latestScanAt(): Long?

    /** Exact normalized-label match. */
    @Query("SELECT * FROM installed_apps WHERE normalizedLabel = :norm LIMIT 1")
    suspend fun findExact(norm: String): InstalledApp?

    /**
     * Prefix match — used when the user said the first word(s) of an
     * app name ("youtube" → "YouTube" / "YouTube Music"). Order by label
     * length ascending so the shortest (= most specific) match wins.
     */
    @Query("SELECT * FROM installed_apps WHERE normalizedLabel LIKE :prefix || '%' ORDER BY LENGTH(normalizedLabel) ASC LIMIT 1")
    suspend fun findByPrefix(prefix: String): InstalledApp?

    /**
     * Substring match — last-resort fallback when no exact / prefix
     * match works. Returns the shortest label that contains the needle
     * so "tube" prefers "YouTube" over a longer label that also
     * happens to contain "tube".
     */
    @Query("SELECT * FROM installed_apps WHERE normalizedLabel LIKE '%' || :needle || '%' ORDER BY LENGTH(normalizedLabel) ASC LIMIT 1")
    suspend fun findByContains(needle: String): InstalledApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<InstalledApp>)

    @Query("DELETE FROM installed_apps")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(apps: List<InstalledApp>) {
        deleteAll()
        upsertAll(apps)
    }
}
