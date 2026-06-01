package com.lazydevs.wristotle.speech.nlu.bank

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ExampleDao {
    @Query("SELECT * FROM nlu_examples WHERE source = 'learned'")
    suspend fun learned(): List<ExampleEntry>

    @Query("SELECT * FROM nlu_examples WHERE source = 'learned' AND intent = :intent")
    suspend fun learnedForIntent(intent: String): List<ExampleEntry>

    @Query("SELECT * FROM nlu_examples WHERE normalizedText = :norm LIMIT 1")
    suspend fun findByNormalized(norm: String): ExampleEntry?

    /** IGNORE on conflict — dedup via [ExampleEntry.normalizedText] unique index. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ExampleEntry): Long

    @Update
    suspend fun update(entry: ExampleEntry)

    @Query("DELETE FROM nlu_examples WHERE source = 'learned'")
    suspend fun deleteLearned()

    /**
     * Delete a single learned row by id. Guarded with `source = 'learned'`
     * so a caller passing a wrong id can't accidentally evict a bundled
     * seed (those aren't user-data and re-bundle on each install).
     */
    @Query("DELETE FROM nlu_examples WHERE id = :id AND source = 'learned'")
    suspend fun deleteLearnedById(id: Long): Int

    @Query("SELECT COUNT(*) FROM nlu_examples WHERE source = 'learned' AND intent = :intent")
    suspend fun countLearnedForIntent(intent: String): Int

    /** Total learned-row count across all intents — used by the backup
     *  card to show "Learned phrases (N)" without paging the full list. */
    @Query("SELECT COUNT(*) FROM nlu_examples WHERE source = 'learned'")
    suspend fun countLearned(): Int
}
