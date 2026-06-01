package com.lazydevs.wristotle.help

import android.content.Context

/**
 * One-shot "should we surface the Help page on this launch?" state.
 *
 * Compares the running build's [com.lazydevs.wristotle.BuildConfig.VERSION_NAME]
 * against the last version we persisted on this device. If they differ
 * (first install: persisted is null; update: persisted is the previous
 * release), the next caller of [consumeOnce] gets back the running
 * version — used by [com.lazydevs.wristotle.ui.MainScreen] to navigate
 * straight to Settings → ❓ Help and pre-fill its search field, so the
 * user lands on the entry that documents what they just got.
 *
 * After [consumeOnce] returns the version once, further calls return
 * null until the *next* install/update. The persisted version is
 * rewritten on construction (not on consume) so an app crash after
 * launch doesn't loop the auto-open on every subsequent start.
 *
 * Singleton, instantiated from [com.lazydevs.wristotle.WristotleApplication].
 */
class WhatsNewState(context: Context, currentVersion: String) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var pending: String? = run {
        val last = prefs.getString(KEY_LAST_SEEN_VERSION, null)
        if (last != currentVersion) {
            // Persist immediately — if we waited until consume, a crash
            // between launch and the first UI tick would replay the
            // auto-open every cold start.
            prefs.edit().putString(KEY_LAST_SEEN_VERSION, currentVersion).apply()
            currentVersion
        } else {
            null
        }
    }

    /** Returns the just-installed version on the first call after a
     *  version change; null on every subsequent call. */
    @Synchronized
    fun consumeOnce(): String? {
        val v = pending
        pending = null
        return v
    }

    companion object {
        private const val PREFS_NAME = "wristotle_whats_new"
        private const val KEY_LAST_SEEN_VERSION = "last_seen_version"
    }
}
