package com.lazydevs.wristotle.alarms

import kotlinx.coroutines.flow.Flow

/**
 * Thin façade over [AlarmDao] — lets callers depend on the abstraction
 * without dragging Room's `@Dao` interface into their type signature.
 * Same shape as `NoteRepository` / `TaskRepository`.
 */
class AlarmRepository(private val dao: AlarmDao) {
    fun observeAll(): Flow<List<AlarmEntity>> = dao.observeAll()
    suspend fun getAll(): List<AlarmEntity> = dao.getAll()
    suspend fun getByHourMinute(hour: Int, minute: Int): List<AlarmEntity> =
        dao.getByHourMinute(hour, minute)
    suspend fun getByHour(hour: Int): List<AlarmEntity> = dao.getByHour(hour)
    suspend fun getById(id: Long): AlarmEntity? = dao.getById(id)
    suspend fun insert(alarm: AlarmEntity): Long = dao.insert(alarm)
    suspend fun update(alarm: AlarmEntity) = dao.update(alarm)
    suspend fun delete(id: Long) = dao.deleteById(id)
}
