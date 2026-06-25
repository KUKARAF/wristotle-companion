// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.codes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CodeDao {
    @Insert
    suspend fun insert(code: CodeEntity)

    @Query("SELECT * FROM codes ORDER BY createdAtEpochMs DESC")
    fun observeAllNewestFirst(): Flow<List<CodeEntity>>

    @Query("SELECT * FROM codes ORDER BY createdAtEpochMs DESC")
    suspend fun allNewestFirst(): List<CodeEntity>

    @Query("UPDATE codes SET label = :label, alias = :alias, format = :format, data = :data WHERE id = :id")
    suspend fun update(id: String, label: String, alias: String, format: String, data: String)

    @Query("DELETE FROM codes WHERE id = :id")
    suspend fun deleteById(id: String)
}
