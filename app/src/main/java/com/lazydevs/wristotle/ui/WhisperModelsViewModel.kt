package com.lazydevs.wristotle.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.speech.whisper.DownloadEvent
import com.lazydevs.wristotle.speech.whisper.ModelCatalog
import com.lazydevs.wristotle.speech.whisper.ModelDownloader
import com.lazydevs.wristotle.speech.whisper.ModelInfo
import com.lazydevs.wristotle.speech.whisper.ModelStorage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "WhisperModelsViewModel"

/** Per-model UI snapshot consumed by [com.lazydevs.wristotle.ui.WhisperModelsCard]. */
data class ModelUiState(
    val info: ModelInfo,
    val isDownloaded: Boolean,
    val isActive: Boolean,
    /** 0.0..1.0 while downloading, null when idle. */
    val progress: Float? = null,
    /** Last error message for this model, cleared on next action. */
    val errorMessage: String? = null,
)

/**
 * Holds the model picker's state and coordinates [ModelDownloader] jobs.
 *
 * Downloading is per-model — multiple downloads can run in parallel (rare, but
 * the UI doesn't prevent it). Auto-activates the first model the user downloads
 * so a working backend is in place before they have to pick one explicitly.
 */
class WhisperModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = ModelStorage(app)
    private val downloader = ModelDownloader(storage)

    private val _models = MutableStateFlow(snapshot())
    val models: StateFlow<List<ModelUiState>> = _models

    private val downloadJobs = mutableMapOf<String, Job>()

    /** Re-reads filesystem + active-id state. Call when the screen resumes. */
    fun refresh() {
        _models.value = snapshot()
    }

    fun download(modelId: String) {
        if (downloadJobs[modelId]?.isActive == true) return
        val info = ModelCatalog.byId(modelId) ?: return

        updateModel(modelId) { it.copy(progress = 0f, errorMessage = null) }
        val job = viewModelScope.launch {
            downloader.download(info).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        val pct = event.totalBytes
                            ?.takeIf { it > 0L }
                            ?.let { event.bytesDownloaded.toFloat() / it.toFloat() }
                        updateModel(modelId) { it.copy(progress = pct) }
                    }
                    DownloadEvent.Complete -> {
                        Log.d(TAG, "download complete: $modelId")
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
                    is DownloadEvent.Failed -> {
                        Log.w(TAG, "download failed: $modelId — ${event.message}", event.cause)
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
        storage.delete(modelId)
        refresh()
    }

    fun setActive(modelId: String) {
        if (!storage.isDownloaded(modelId)) return
        storage.activeModelId = modelId
        refresh()
    }

    private fun updateModel(modelId: String, transform: (ModelUiState) -> ModelUiState) {
        _models.value = _models.value.map { if (it.info.id == modelId) transform(it) else it }
    }

    private fun snapshot(): List<ModelUiState> {
        val active = storage.activeModelId
        return ModelCatalog.all.map { info ->
            ModelUiState(
                info = info,
                isDownloaded = storage.isDownloaded(info.id),
                isActive = info.id == active,
            )
        }
    }
}
