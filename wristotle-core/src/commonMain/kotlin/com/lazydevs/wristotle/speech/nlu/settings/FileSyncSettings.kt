// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import com.lazydevs.wristotle.speech.nlu.store.KeyValueStore
import com.lazydevs.wristotle.speech.nlu.store.getEnum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Wire format for the rendered payload. */
enum class FileSyncFormat { Markdown, PlainText, Json }

/** Per-render granularity. */
enum class FileSyncGranularity { OneFilePerEntity, AppendToSingleFile }

/**
 * Per-render flags the user picked in Settings. Pure-data; kept as a
 * data class so future knobs (Obsidian-flavour wikilinks, frontmatter
 * on/off, etc.) land without breaking renderer signatures.
 */
data class FileSyncFormatOptions(
    val format: FileSyncFormat = FileSyncFormat.Markdown,
    val granularity: FileSyncGranularity = FileSyncGranularity.OneFilePerEntity,
)

/**
 * Per-entity sync settings — one instance per entity type. Notes today,
 * Conversations next; each gets its own scope, persisted under the
 * `file_sync_<scope>` SharedPreferences file on Android.
 *
 * Persisted fields:
 *
 * - `enabled` — master toggle.
 * - `folderUri` — opaque platform-specific folder pointer (Android:
 *   `content://…` from SAF; iOS: a security-scoped bookmark URL).
 *   Empty when the user hasn't picked yet. The platform layer owns
 *   the permission grants + revocation handling; this class is just
 *   storage.
 * - `format` / `granularity` — see [FileSyncFormatOptions].
 * - `deleteCascades` — when an entity is deleted in Wristotle, also
 *   delete the file in the vault. Default ON.
 *
 * R4 batch 10 — lifted from :app onto the [KeyValueStore] seam. The
 * `scope` is captured by the prefs-file name on the Android side
 * (each scope has its own KeyValueStore instance pointing at
 * `file_sync_<scope>`); the lifted class no longer needs the scope
 * string for any internal logic.
 */
class FileSyncSettings(
    private val store: KeyValueStore,
    /**
     * Per-scope default granularity. Notes default to one-file-per-note;
     * conversations default to append-to-single-file because per-query
     * files would flood the vault. Only read on first install when no
     * KEY_GRANULARITY value is persisted yet.
     */
    private val defaultGranularity: FileSyncGranularity = FileSyncGranularity.OneFilePerEntity,
) {

    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled

    private val _folderUri = MutableStateFlow(store.getString(KEY_FOLDER_URI, ""))
    val folderUri: StateFlow<String> = _folderUri

    private val _format = MutableStateFlow(readFormat())
    val format: StateFlow<FileSyncFormat> = _format

    private val _granularity = MutableStateFlow(readGranularity())
    val granularity: StateFlow<FileSyncGranularity> = _granularity

    private val _deleteCascades = MutableStateFlow(store.getBoolean(KEY_DELETE_CASCADES, true))
    val deleteCascades: StateFlow<Boolean> = _deleteCascades

    fun formatOptions(): FileSyncFormatOptions =
        FileSyncFormatOptions(format = _format.value, granularity = _granularity.value)

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        store.putBoolean(KEY_ENABLED, value)
        _enabled.value = value
    }

    fun setFolderUri(value: String) {
        val trimmed = value.trim()
        if (_folderUri.value == trimmed) return
        store.putString(KEY_FOLDER_URI, trimmed)
        _folderUri.value = trimmed
    }

    fun setFormat(value: FileSyncFormat) {
        if (_format.value == value) return
        store.putString(KEY_FORMAT, value.name)
        _format.value = value
    }

    fun setGranularity(value: FileSyncGranularity) {
        if (_granularity.value == value) return
        store.putString(KEY_GRANULARITY, value.name)
        _granularity.value = value
    }

    fun setDeleteCascades(value: Boolean) {
        if (_deleteCascades.value == value) return
        store.putBoolean(KEY_DELETE_CASCADES, value)
        _deleteCascades.value = value
    }

    private fun readFormat(): FileSyncFormat =
        store.getEnum(KEY_FORMAT, FileSyncFormat.Markdown)

    private fun readGranularity(): FileSyncGranularity =
        store.getEnum(KEY_GRANULARITY, defaultGranularity)

    companion object {
        /** Android-side prefs file name template — the scope is baked into the suffix. */
        fun prefsName(scope: String): String = "file_sync_$scope"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FORMAT = "format"
        private const val KEY_GRANULARITY = "granularity"
        private const val KEY_DELETE_CASCADES = "delete_cascades"
    }
}
