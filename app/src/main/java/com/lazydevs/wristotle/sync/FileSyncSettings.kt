package com.lazydevs.wristotle.sync

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-entity sync settings — one instance per entity type. Notes today,
 * Conversations next; each gets its own `FileSyncSettings(context,
 * scope = "notes")`. State lives in a per-scope `SharedPreferences` file
 * (`file_sync_<scope>.xml`) so the entity sections in Settings don't
 * step on each other.
 *
 * Persisted fields:
 *
 * - `enabled` — master toggle.
 * - `folderUri` — `content://…` URI from `ACTION_OPEN_DOCUMENT_TREE`;
 *   empty when the user hasn't picked yet. The coordinator is
 *   responsible for [android.content.ContentResolver.takePersistableUriPermission]
 *   and detecting revocation; this class is just storage.
 * - `format` / `granularity` — see [FileSyncFormatOptions].
 * - `deleteCascades` — when an entity is deleted in Wristotle, also
 *   delete the file in the vault. Default ON (user-confirmed for the
 *   Notes scope). Surfaced as a Settings toggle.
 */
class FileSyncSettings(
    context: Context,
    private val scope: String,
    /**
     * Per-scope default granularity. Notes default to one-file-per-note
     * (vault-style); conversations default to append-to-single-file
     * (daily-log shape) because per-query files would flood the vault.
     * Only read on first install when no `KEY_GRANULARITY` value is
     * persisted yet — subsequent runs respect whatever the user picked.
     */
    private val defaultGranularity: FileSyncGranularity = FileSyncGranularity.OneFilePerEntity,
) {
    private val prefs = context.getSharedPreferences("file_sync_$scope", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    private val _folderUri = MutableStateFlow(prefs.getString(KEY_FOLDER_URI, "").orEmpty())
    val folderUri: StateFlow<String> = _folderUri

    private val _format = MutableStateFlow(readFormat())
    val format: StateFlow<FileSyncFormat> = _format

    private val _granularity = MutableStateFlow(readGranularity())
    val granularity: StateFlow<FileSyncGranularity> = _granularity

    private val _deleteCascades = MutableStateFlow(prefs.getBoolean(KEY_DELETE_CASCADES, true))
    val deleteCascades: StateFlow<Boolean> = _deleteCascades

    fun formatOptions(): FileSyncFormatOptions =
        FileSyncFormatOptions(format = _format.value, granularity = _granularity.value)

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        prefs.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    fun setFolderUri(value: String) {
        val trimmed = value.trim()
        if (_folderUri.value == trimmed) return
        prefs.edit { putString(KEY_FOLDER_URI, trimmed) }
        _folderUri.value = trimmed
    }

    fun setFormat(value: FileSyncFormat) {
        if (_format.value == value) return
        prefs.edit { putString(KEY_FORMAT, value.name) }
        _format.value = value
    }

    fun setGranularity(value: FileSyncGranularity) {
        if (_granularity.value == value) return
        prefs.edit { putString(KEY_GRANULARITY, value.name) }
        _granularity.value = value
    }

    fun setDeleteCascades(value: Boolean) {
        if (_deleteCascades.value == value) return
        prefs.edit { putBoolean(KEY_DELETE_CASCADES, value) }
        _deleteCascades.value = value
    }

    private fun readFormat(): FileSyncFormat =
        prefs.getString(KEY_FORMAT, null)
            ?.let { name -> runCatching { FileSyncFormat.valueOf(name) }.getOrNull() }
            ?: FileSyncFormat.Markdown

    private fun readGranularity(): FileSyncGranularity =
        prefs.getString(KEY_GRANULARITY, null)
            ?.let { name -> runCatching { FileSyncGranularity.valueOf(name) }.getOrNull() }
            ?: defaultGranularity

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_FORMAT = "format"
        const val KEY_GRANULARITY = "granularity"
        const val KEY_DELETE_CASCADES = "delete_cascades"
    }
}
