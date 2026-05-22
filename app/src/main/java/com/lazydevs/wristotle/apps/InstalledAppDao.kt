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

    /** Exact match on the real package id — used to confirm an aliased target
     *  is still installed before honoring the alias. */
    @Query("SELECT * FROM installed_apps WHERE packageId = :packageId LIMIT 1")
    suspend fun findByPackageId(packageId: String): InstalledApp?

    /** Exact normalized-label match. */
    @Query("SELECT * FROM installed_apps WHERE normalizedLabel = :norm LIMIT 1")
    suspend fun findExact(norm: String): InstalledApp?

    /** Exact normalized-package match. Called after [findExact] misses. */
    @Query("SELECT * FROM installed_apps WHERE normalizedPackage = :norm LIMIT 1")
    suspend fun findExactByPackage(norm: String): InstalledApp?

    /**
     * Prefix match — used when the user said the first word(s) of an
     * app name ("youtube" → "YouTube" / "YouTube Music"). Order by label
     * length ascending so the shortest (= most specific) match wins.
     */
    @Query("SELECT * FROM installed_apps WHERE normalizedLabel LIKE :prefix || '%' ORDER BY LENGTH(normalizedLabel) ASC LIMIT 1")
    suspend fun findByPrefix(prefix: String): InstalledApp?

    /** Same as [findByPrefix] but against the normalized package column. */
    @Query("SELECT * FROM installed_apps WHERE normalizedPackage LIKE :prefix || '%' ORDER BY LENGTH(normalizedPackage) ASC LIMIT 1")
    suspend fun findByPrefixOfPackage(prefix: String): InstalledApp?

    /**
     * Substring match — last-resort fallback when no exact / prefix
     * match works. Returns the shortest label that contains the needle
     * so "tube" prefers "YouTube" over a longer label that also
     * happens to contain "tube".
     */
    @Query("SELECT * FROM installed_apps WHERE normalizedLabel LIKE '%' || :needle || '%' ORDER BY LENGTH(normalizedLabel) ASC LIMIT 1")
    suspend fun findByContains(needle: String): InstalledApp?

    /** Same as [findByContains] but against the normalized package column. */
    @Query("SELECT * FROM installed_apps WHERE normalizedPackage LIKE '%' || :needle || '%' ORDER BY LENGTH(normalizedPackage) ASC LIMIT 1")
    suspend fun findByContainsInPackage(needle: String): InstalledApp?

    /**
     * Reverse substring match — the spoken needle CONTAINS a label.
     * Catches Whisper mishearings that bolt extra characters onto an
     * app name ("Absorbed" → "Absorb", "Spotify Music" → "Spotify").
     * Restricted to labels ≥4 chars so a hypothetical 2-letter app
     * doesn't get picked for arbitrary needles. Longest label wins so
     * "youtube music" prefers "YouTube Music" over "YouTube" when
     * both are installed.
     */
    @Query("SELECT * FROM installed_apps WHERE :needle LIKE '%' || normalizedLabel || '%' AND LENGTH(normalizedLabel) >= 4 ORDER BY LENGTH(normalizedLabel) DESC LIMIT 1")
    suspend fun findByReverseContains(needle: String): InstalledApp?

    /** Same as [findByReverseContains] but against the normalized package column. */
    @Query("SELECT * FROM installed_apps WHERE :needle LIKE '%' || normalizedPackage || '%' AND LENGTH(normalizedPackage) >= 4 ORDER BY LENGTH(normalizedPackage) DESC LIMIT 1")
    suspend fun findByReverseContainsOfPackage(needle: String): InstalledApp?

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
