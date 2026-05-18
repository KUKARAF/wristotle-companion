package com.lazydevs.wristotle.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.speech.model.DownloadStreamEvent
import com.lazydevs.wristotle.speech.model.ModelFileStorage
import com.lazydevs.wristotle.speech.model.ResumableDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-model UI snapshot consumed by [WhisperModelsCard] / [NluModelsCard].
 * Generic over the model-info type so both card families share one
 * shape — historically there were near-identical `ModelUiState` and
 * `NluModelUiState` data classes that drifted independently.
 */
data class ModelUiState<InfoT>(
    val info: InfoT,
    val isDownloaded: Boolean,
    val isActive: Boolean,
    /** 0.0..1.0 while downloading, null when idle. */
    val progress: Float? = null,
    /** Last error message for this model, cleared on next action. */
    val errorMessage: String? = null,
)

/**
 * Shared base for the per-family model picker ViewModels. Owns the
 * download / cancel / delete / setActive / snapshot machinery — every
 * model-family ViewModel had a copy of this with only the storage,
 * catalog, and id/url accessor types differing.
 *
 * Subclasses declare:
 *   - [storage] — backing [ModelFileStorage] for this family
 *   - [catalog] — the family's catalog list
 *   - [idOf] / [urlOf] — projections out of [InfoT]
 *
 * Override [onAfterDelete] to evict caches keyed by file path (e.g.
 * the native Whisper recognizer cache); default is a no-op.
 *
 * Downloads run in [viewModelScope]; per-model jobs let the user
 * cancel one without affecting parallel downloads of other models.
 */
abstract class ModelsViewModel<InfoT>(app: Application) : AndroidViewModel(app) {

    protected abstract val tag: String
    protected abstract val storage: ModelFileStorage
    protected abstract val catalog: List<InfoT>
    protected abstract fun idOf(info: InfoT): String
    protected abstract fun urlOf(info: InfoT): String

    /**
     * Hook fired after [storage.delete] succeeds. Path is the absolute
     * path of the file that was removed — subclasses that cache native
     * handles keyed by path should evict here so a re-download loads
     * the new file instead of returning a stale handle.
     */
    protected open fun onAfterDelete(modelId: String, deletedPath: String) {}

    private val downloader = ResumableDownloader()

    private val _models: MutableStateFlow<List<ModelUiState<InfoT>>> = MutableStateFlow(emptyList())
    val models: StateFlow<List<ModelUiState<InfoT>>> = _models

    /**
     * Per-model download jobs. Concurrent because writes happen from two
     * dispatchers: the launching coroutine (main) puts the job in, while
     * `invokeOnCompletion` (which fires on whichever dispatcher completed
     * the job — typically Dispatchers.IO) removes it.
     */
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    /**
     * Snapshot is computed lazily in [refresh] — the abstract members
     * aren't available at construction time, so we can't compute the
     * initial snapshot in the property initialiser. Callers (UI) call
     * refresh() in onResume anyway.
     */
    fun refresh() {
        _models.value = snapshot()
    }

    fun download(modelId: String) {
        if (downloadJobs[modelId]?.isActive == true) return
        val info = catalog.firstOrNull { idOf(it) == modelId } ?: return

        updateModel(modelId) { it.copy(progress = 0f, errorMessage = null) }
        val job = viewModelScope.launch {
            downloader.download(urlOf(info), storage.modelFile(idOf(info))).collect { event ->
                when (event) {
                    is DownloadStreamEvent.Progress -> {
                        val pct = event.totalBytes
                            ?.takeIf { it > 0L }
                            ?.let { event.bytesDownloaded.toFloat() / it.toFloat() }
                        updateModel(modelId) { it.copy(progress = pct) }
                    }
                    DownloadStreamEvent.Complete -> {
                        Log.d(tag, "download complete: $modelId")
                        val firstDownload = storage.activeModelId == null
                        if (firstDownload) storage.activeModelId = modelId
                        updateModel(modelId) {
                            it.copy(
                                progress = null,
                                isDownloaded = true,
                                isActive = firstDownload || it.isActive,
                            )
                        }
                    }
                    is DownloadStreamEvent.Failed -> {
                        Log.w(tag, "download failed: $modelId — ${event.message}", event.cause)
                        updateModel(modelId) {
                            it.copy(progress = null, errorMessage = event.message)
                        }
                    }
                }
            }
        }
        downloadJobs[modelId] = job
        job.invokeOnCompletion { downloadJobs.remove(modelId) }
    }

    fun cancelDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        updateModel(modelId) { it.copy(progress = null) }
    }

    fun delete(modelId: String) {
        if (downloadJobs[modelId]?.isActive == true) cancelDownload(modelId)
        val pathBeingDeleted = storage.modelFile(modelId).absolutePath
        storage.delete(modelId)
        onAfterDelete(modelId, pathBeingDeleted)
        refresh()
    }

    fun setActive(modelId: String) {
        if (!storage.isDownloaded(modelId)) {
            Log.w(tag, "setActive($modelId) ignored — not downloaded")
            return
        }
        Log.d(tag, "setActive: $modelId")
        storage.activeModelId = modelId
        refresh()
    }

    private fun updateModel(modelId: String, transform: (ModelUiState<InfoT>) -> ModelUiState<InfoT>) {
        _models.value = _models.value.map { if (idOf(it.info) == modelId) transform(it) else it }
    }

    private fun snapshot(): List<ModelUiState<InfoT>> {
        val active = storage.activeModelId
        return catalog.map { info ->
            ModelUiState(
                info = info,
                isDownloaded = storage.isDownloaded(idOf(info)),
                isActive = idOf(info) == active,
            )
        }
    }
}
