// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.transport

import android.content.Context

/**
 * Caches the last version the Wristotle **watch app** reported. A watchapp only
 * runs when launched, so it can't be queried on demand — instead it piggybacks
 * its version onto the launch ping ([MessageKeys.WATCH_APP_VERSION]), which the
 * [com.lazydevs.wristotle.service.PebbleListenerService] records here. The
 * diagnostics bundle reads it back with a "last seen" timestamp.
 *
 * Backed by a named [android.content.SharedPreferences], so constructing a fresh
 * instance from any Context (the service or the diagnostics builder) sees the
 * same data — no need to thread a singleton through the Application.
 */
class WatchInfoStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last reported watch-app version, or null if the watch app hasn't been
     *  opened since this companion build started caching it. */
    val watchAppVersion: String? get() = prefs.getString(KEY_VERSION, null)

    /** Epoch millis when [watchAppVersion] was last reported, or 0 if never. */
    val watchAppVersionSeenAt: Long get() = prefs.getLong(KEY_SEEN_AT, 0L)

    fun recordWatchAppVersion(version: String, nowMs: Long) {
        prefs.edit()
            .putString(KEY_VERSION, version)
            .putLong(KEY_SEEN_AT, nowMs)
            .apply()
    }

    private companion object {
        const val PREFS = "wristotle-watch-info"
        const val KEY_VERSION = "watch_app_version"
        const val KEY_SEEN_AT = "watch_app_version_seen_at"
    }
}
