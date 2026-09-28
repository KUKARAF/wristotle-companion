// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.notesserver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who we're signed in as on the notes server. */
data class NotesServerAccount(val token: String, val label: String)

/** What the sync engine needs from the sign-in state (fakeable in tests). */
interface NotesServerSession {
    val account: StateFlow<NotesServerAccount?>
    val token: String?
    fun clear()
}

/**
 * Persists the rust_note device token. The token is opaque, slides to 90
 * days after last use and has no refresh flow — a 401 means "log in again".
 * Stored in app-private prefs, like the MCP server credentials.
 */
class NotesServerAuth(context: Context) : NotesServerSession {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(load())
    override val account: StateFlow<NotesServerAccount?> = _account.asStateFlow()

    override val token: String? get() = _account.value?.token

    fun save(token: String, label: String) {
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_LABEL, label).apply()
        _account.value = NotesServerAccount(token, label)
    }

    override fun clear() {
        prefs.edit().clear().apply()
        _account.value = null
    }

    private fun load(): NotesServerAccount? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return NotesServerAccount(token, prefs.getString(KEY_LABEL, null).orEmpty())
    }

    companion object {
        private const val PREFS_NAME = "wristotle_notes_server"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_LABEL = "account_label"

        /**
         * Pulls the device token out of the login redirect. Current rust_note
         * redirects to `dev.rustnote.app://auth?token=…`; older builds used
         * `http://tauri.localhost/#token=…`, so accept a fragment too.
         */
        fun tokenFromRedirect(url: String): String? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val isAppScheme = uri.scheme == NotesServerConfig.APP_REDIRECT_SCHEME
            val isTauri = uri.host == "tauri.localhost"
            if (!isAppScheme && !isTauri) return null
            uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let { return it }
            val fragment = uri.fragment ?: return null
            return fragment.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "token" }
                ?.get(1)?.let(Uri::decode)?.takeIf { it.isNotBlank() }
        }
    }
}
