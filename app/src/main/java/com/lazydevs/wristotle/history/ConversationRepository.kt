package com.lazydevs.wristotle.history

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over [ConversationDao] that owns the 30-day retention policy.
 *
 * Inserts also trigger a prune so the table can't grow unbounded even if the
 * process is never restarted. The prune itself is cheap (single indexed
 * range delete) and runs on the IO dispatcher implicitly via Room's suspend
 * support.
 */
class ConversationRepository(private val dao: ConversationDao) {

    fun observeAll(): Flow<List<ConversationEntry>> = dao.observeAllNewestFirst()

    suspend fun add(entry: ConversationEntry) {
        dao.insert(entry)
        prune()
    }

    suspend fun clearAll() {
        dao.deleteAll()
    }

    /** Called from WristotleApplication on startup; safe to call any time. */
    suspend fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val removed = dao.pruneOlderThan(cutoff)
        if (removed > 0) Log.d(TAG, "Pruned $removed conversation entries older than $RETENTION_DAYS days")
    }

    companion object {
        private const val TAG = "ConversationRepository"
        private const val RETENTION_DAYS = 30L
        private val RETENTION_MS = TimeUnit.DAYS.toMillis(RETENTION_DAYS)
    }
}
