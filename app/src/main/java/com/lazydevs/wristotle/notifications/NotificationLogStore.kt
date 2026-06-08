// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "NotificationLogStore"

/**
 * Funnel for the listener-side write path: the
 * [com.lazydevs.wristotle.media.MediaSessionsListener] hands each
 * `StatusBarNotification` here, this class decides whether to log it
 * (filter + opt-in toggle) and writes the row through [dao] off the
 * binder thread.
 *
 * Phase A: [enabledProvider] hard-defaults to `false` in
 * [com.lazydevs.wristotle.WristotleApplication], so no rows land yet.
 * Phase C swaps in a SharedPrefs-backed provider.
 *
 * Body / title / extras are never read — only the routing metadata that
 * [NotificationFilter] needs to bucket per-conversation.
 */
class NotificationLogStore(
    private val dao: NotificationPostDao,
    private val scope: CoroutineScope,
    private val enabledProvider: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun record(sbn: StatusBarNotification) {
        if (!enabledProvider()) return
        val flags = sbn.notification?.flags ?: 0
        if (!NotificationFilter.isActionable(sbn.packageName, flags)) return

        val post = NotificationPost(
            packageName = sbn.packageName,
            conversationKey = NotificationFilter.conversationKey(
                packageName = sbn.packageName,
                shortcutId = sbn.notification?.shortcutId,
                channelId = sbn.notification?.channelId,
                tag = sbn.tag,
                id = sbn.id,
            ),
            postedAtEpochMs = clock(),
        )
        scope.launch(Dispatchers.IO) {
            try {
                dao.insert(post)
            } catch (t: Throwable) {
                Log.w(TAG, "insert failed", t)
            }
        }
    }

    /** Phase E retention — drop rows older than [cutoffEpochMs]. */
    fun prune(cutoffEpochMs: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                val n = dao.pruneOlderThan(cutoffEpochMs)
                if (n > 0) Log.d(TAG, "pruned $n row(s) older than $cutoffEpochMs")
            } catch (t: Throwable) {
                Log.w(TAG, "prune failed", t)
            }
        }
    }

    /** Phase C "Clear log" button. */
    fun deleteAll() {
        scope.launch(Dispatchers.IO) {
            try {
                val n = dao.deleteAll()
                Log.d(TAG, "cleared $n row(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "clear failed", t)
            }
        }
    }

    companion object {
        /** Phase E default — drop rows older than 7 days. The brief only
         *  reads "today" so anything past the window is dead weight. */
        val DEFAULT_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Notification flag constants live on the Android API surface;
         *  keep a project-local alias so a smoke test can construct one
         *  without importing Android into a unit test. */
        const val FLAG_GROUP_SUMMARY: Int = Notification.FLAG_GROUP_SUMMARY
    }
}
