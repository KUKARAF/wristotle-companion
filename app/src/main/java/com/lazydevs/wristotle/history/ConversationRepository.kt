package com.lazydevs.wristotle.history

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over [ConversationDao] that owns the retention policy.
 *
 * Retention window is user-configurable via [ConversationSettings]; the
 * repository reads the current value each time it prunes so a change to
 * the setting applies on the next insert without any restart.
 *
 * Inserts also trigger a prune so the table can't grow unbounded even if
 * the process is never restarted. The prune itself is cheap (single
 * indexed range delete) and runs on the IO dispatcher implicitly via
 * Room's suspend support.
 */
class ConversationRepository(
    private val dao: ConversationDao,
    private val settings: ConversationSettings,
) {

    fun observeAll(): Flow<List<ConversationEntry>> = dao.observeAllNewestFirst()

    suspend fun add(entry: ConversationEntry) {
        dao.insert(entry)
        prune()
    }

    suspend fun clearAll() {
        dao.deleteAll()
    }

    /** Called from WristotleApplication on startup AND after the user changes
     *  the retention window in Settings, so a shorter window applies immediately
     *  instead of waiting for the next dictation. */
    suspend fun prune() {
        val days = settings.retentionDays.value
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val removed = dao.pruneOlderThan(cutoff)
        if (removed > 0) Log.d(TAG, "Pruned $removed conversation entries older than $days days")
    }

    /** Number of entries that would be deleted if the retention window were
     *  set to [days]. Used by the Settings screen to preview the impact
     *  before the user confirms a shrink. */
    suspend fun countOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        return dao.countOlderThan(cutoff)
    }

    companion object {
        private const val TAG = "ConversationRepository"
    }
}
