// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.speech.whisper.ImportResult
import com.lazydevs.wristotle.speech.whisper.ModelCatalog
import com.lazydevs.wristotle.speech.whisper.ModelInfo
import com.lazydevs.wristotle.speech.whisper.ModelStorage
import com.lazydevs.wristotle.transport.PebbleCompanionDetector
import com.lazydevs.wristotle.ui.components.WHILE_SUBSCRIBED_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-row UI snapshot for a user-imported model. Differs from
 * [ModelUiState] (catalog rows) in that there's no `info` payload,
 * no URL, no download state — an imported model is always on disk
 * by definition. The card renders these in a dedicated "Imported"
 * section beneath the catalog tiers.
 */
data class ImportedModelUiState(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val isActive: Boolean,
)

class WhisperModelsViewModel(app: Application) : ModelsViewModel<ModelInfo>(app) {
    override val tag = "WhisperModelsViewModel"
    override val storage = ModelStorage(app)
    override val catalog = ModelCatalog.all
    override fun idOf(info: ModelInfo) = info.id
    override fun urlOf(info: ModelInfo) = info.url

    private val detector = (app as WristotleApplication).pebbleCompanionDetector

    /**
     * BLE-companion classification, surfaced so the card can render the
     * "rePebble bypasses Whisper for watch dictation" explainer instead
     * of (or alongside) the model rows. See [PebbleCompanionDetector].
     */
    val pebbleCompanion: StateFlow<PebbleCompanionDetector.State> = detector.state

    /** Whisper-Models-specific dismissal — hides the explainer banner
     *  but does NOT reveal the model rows. The two are intentionally
     *  separate: "Got it" = "I understand, hide the explainer";
     *  "Show models anyway" = "I want to see the models anyway." */
    val noticeDismissed: StateFlow<Boolean> = detector.whisperModelsNoticeDismissed
    fun dismissNotice() = detector.dismissWhisperModelsNotice()

    /** Sticky reveal — set when the user explicitly taps "Show models
     *  anyway". Persists across cold starts so the user doesn't have
     *  to opt-in repeatedly. */
    val modelsRevealedAnyway: StateFlow<Boolean> = detector.whisperModelsRevealed
    fun revealModelsAnyway() = detector.revealWhisperModelsUnderRePebble()
    /** Inverse of [revealModelsAnyway] — collapses the model rows again
     *  if the user changes their mind. */
    fun hideModelsAnyway() = detector.hideWhisperModelsUnderRePebble()

    // ── Imported models ───────────────────────────────────────────────

    private val _importedModels = MutableStateFlow<List<ImportedModelUiState>>(emptyList())
    /** User-imported models, distinct from the catalog list. Sorted by
     *  display name so the order in the card is stable across imports. */
    val importedModels: StateFlow<List<ImportedModelUiState>> = _importedModels.asStateFlow()

    private val _importInProgress = MutableStateFlow(false)
    /** True while a stream is being copied + validated. The card uses
     *  this to disable the Import button so the user can't queue a
     *  second pick mid-copy. */
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    private val _lastImportError = MutableStateFlow<String?>(null)
    /** Sticky error message from the most recent failed import, cleared
     *  on the next call to [importFromUri] or [clearImportError]. */
    val lastImportError: StateFlow<String?> = _lastImportError.asStateFlow()

    /**
     * Override the base check to consider imported models as well —
     * otherwise activating an imported model leaves
     * `models.none { isActive }` true (the catalog list is unchanged)
     * and the Settings-tab attention badge stays on forever.
     */
    override val attentionNeeded: StateFlow<Boolean> = combine(models, _importedModels) { catalog, imported ->
        val anyDownloaded = catalog.any { it.isDownloaded } || imported.isNotEmpty()
        val anyActive = catalog.any { it.isActive } || imported.any { it.isActive }
        !anyDownloaded || !anyActive
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(WHILE_SUBSCRIBED_MS),
            initialValue = true,
        )

    init { refresh() }

    /** Extended to also refresh the imported list. The catalog refresh
     *  uses the base implementation; imported models use [snapshotImported]. */
    override fun refresh() {
        super.refresh()
        _importedModels.value = snapshotImported()
    }

    /**
     * Imports the file at [uri] (typically picked via
     * `ACTION_OPEN_DOCUMENT`) as a new imported-model row. The copy +
     * magic-bytes validation runs on [Dispatchers.IO] — the UI stays
     * responsive even for ~half-GB models.
     *
     * If the import succeeds AND no model is currently active, the new
     * import becomes active automatically (mirrors the "first download
     * auto-activates" behaviour in the catalog flow).
     */
    fun importFromUri(uri: Uri, displayName: String) {
        if (_importInProgress.value) return
        _importInProgress.value = true
        _lastImportError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val input = app.contentResolver.openInputStream(uri)
                if (input == null) {
                    _lastImportError.value = "Couldn't open the picked file."
                    return@launch
                }
                val cleanedName = displayName.trim().ifEmpty { "Imported model" }
                when (val result = storage.importFromStream(input, cleanedName)) {
                    is ImportResult.Success -> {
                        if (storage.activeModelId == null) storage.activeModelId = result.id
                        refresh()
                    }
                    is ImportResult.InvalidFormat ->
                        _lastImportError.value = "Not a whisper.cpp model: ${result.detail}"
                    is ImportResult.IoError ->
                        _lastImportError.value =
                            "Import failed: ${result.cause.message ?: result.cause::class.simpleName ?: "unknown error"}"
                }
            } finally {
                _importInProgress.value = false
            }
        }
    }

    /** Deletes an imported model + its display-name prefs entry.
     *  Evicts the cached native recognizer for this path so a future
     *  re-import of the same filename can't return a stale handle. */
    fun deleteImported(importedId: String) {
        val pathBeingDeleted = storage.modelFile(importedId).absolutePath
        storage.deleteImported(importedId)
        (getApplication<Application>() as? WristotleApplication)
            ?.evictRecognizer(pathBeingDeleted)
        refresh()
    }

    fun clearImportError() {
        _lastImportError.value = null
    }

    private fun snapshotImported(): List<ImportedModelUiState> {
        val active = storage.activeModelId
        return storage.importedIds().map { id ->
            ImportedModelUiState(
                id = id,
                displayName = storage.importedDisplayName(id),
                sizeBytes = storage.modelFile(id).length(),
                isActive = id == active,
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Evict the native Whisper recognizer cache in [WristotleApplication]
     * keyed by absolute path — without this, re-downloading the same
     * model returns the cached recognizer pointing at the old (now-
     * deleted) file instead of loading the fresh one.
     */
    override fun onAfterDelete(modelId: String, deletedPath: String) {
        (getApplication<Application>() as? WristotleApplication)
            ?.evictRecognizer(deletedPath)
    }
}