package com.lazydevs.wristotle.notes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: Note): Long

    /** Newest first — what the Notes screen renders. Flow for live updates. */
    @Query("SELECT * FROM notes ORDER BY createdAtEpochMs DESC")
    fun observeAllNewestFirst(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Note?

    /** Newest note by creation timestamp — backs the "append to previous note" path. */
    @Query("SELECT * FROM notes ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun findMostRecent(): Note?

    /** Replace body + bump timestamp; used by AppendNote so the updated note
     *  bubbles to the top of the list. */
    @Query("UPDATE notes SET body = :body, createdAtEpochMs = :timestamp WHERE id = :id")
    suspend fun updateBodyAndTimestamp(id: Long, body: String, timestamp: Long)

    /** One-shot snapshot used by FIFO pruning to find the ids beyond the cap. */
    @Query("SELECT id FROM notes ORDER BY createdAtEpochMs DESC LIMIT :limit OFFSET :offset")
    suspend fun idsBeyond(offset: Int, limit: Int): List<Long>

    @Query("SELECT * FROM notes ORDER BY createdAtEpochMs DESC LIMIT :limit OFFSET :offset")
    suspend fun notesBeyond(offset: Int, limit: Int): List<Note>

    @Query("UPDATE notes SET audioFilePath = :path WHERE id = :id")
    suspend fun setAudioFilePath(id: Long, path: String?)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int
}
