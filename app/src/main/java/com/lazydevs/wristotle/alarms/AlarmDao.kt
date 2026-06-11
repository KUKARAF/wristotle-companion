// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.alarms

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE hour = :hour AND minute = :minute")
    suspend fun getByHourMinute(hour: Int, minute: Int): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE hour = :hour")
    suspend fun getByHour(hour: Int): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): AlarmEntity?

    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM alarms")
    suspend fun count(): Int
}