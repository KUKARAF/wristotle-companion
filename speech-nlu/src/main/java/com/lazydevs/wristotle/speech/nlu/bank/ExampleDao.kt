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

    @Query("SELECT COUNT(*) FROM nlu_examples WHERE source = 'learned' AND intent = :intent")
    suspend fun countLearnedForIntent(intent: String): Int
}
