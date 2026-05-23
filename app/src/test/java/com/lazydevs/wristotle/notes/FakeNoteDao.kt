package com.lazydevs.wristotle.notes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [NoteDao] for unit tests. Mirrors the Room implementation's
 * semantics (auto-id, newest-first ordering, ORDER BY createdAtEpochMs DESC
 * for LIMIT/OFFSET queries).
 */
class FakeNoteDao : NoteDao {

    private val rows = mutableListOf<Note>()
    private var nextId: Long = 1
    private val flow = MutableStateFlow<List<Note>>(emptyList())

    private fun emit() { flow.value = newestFirst() }
    private fun newestFirst(): List<Note> = rows.sortedByDescending { it.createdAtEpochMs }

    override suspend fun insert(note: Note): Long {
        val withId = note.copy(id = nextId++)
        rows.add(withId)
        emit()
        return withId.id
    }

    override fun observeAllNewestFirst(): Flow<List<Note>> = flow

    override suspend fun findById(id: Long): Note? = rows.firstOrNull { it.id == id }

    override suspend fun findMostRecent(): Note? = newestFirst().firstOrNull()

    override suspend fun idsBeyond(offset: Int, limit: Int): List<Long> =
        newestFirst().drop(offset).take(limit).map { it.id }

    override suspend fun notesBeyond(offset: Int, limit: Int): List<Note> =
        newestFirst().drop(offset).take(limit)

    override suspend fun setAudioFilePath(id: Long, path: String?) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) {
            rows[idx] = rows[idx].copy(audioFilePath = path)
            emit()
        }
    }

    override suspend fun updateBodyAndTimestamp(id: Long, body: String, timestamp: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) {
            rows[idx] = rows[idx].copy(body = body, createdAtEpochMs = timestamp)
            emit()
        }
    }

    override suspend fun deleteById(id: Long) {
        if (rows.removeAll { it.id == id }) emit()
    }

    override suspend fun deleteAll() {
        if (rows.isNotEmpty()) { rows.clear(); emit() }
    }

    override suspend fun count(): Int = rows.size

    override suspend fun allForBackup(): List<Note> = rows.sortedBy { it.id }
}
