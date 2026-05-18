package com.lazydevs.wristotle.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.speech.model.DownloadStreamEvent
import com.lazydevs.wristotle.speech.model.ResumableDownloader
import com.lazydevs.wristotle.speech.nlu.model.NluModelCatalog
import com.lazydevs.wristotle.speech.nlu.model.NluModelInfo
import com.lazydevs.wristotle.speech.nlu.model.NluModelStorage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NluModelsViewModel"

/** Per-model UI snapshot consumed by [NluModelsCard]. Mirrors [ModelUiState]. */
data class NluModelUiState(
    val info: NluModelInfo,
    val isDownloaded: Boolean,
    val isActive: Boolean,
    /** 0.0..1.0 while downloading, null when idle. */
    val progress: Float? = null,
    /** Last error message for this model, cleared on next action. */
    val errorMessage: String? = null,
)

/**
 * Twin of [WhisperModelsViewModel] for the NLU sentence-encoder model
 * catalogue. Same download / activate / delete coordination, separate
 * storage + catalog so the two model families don't collide.
 *
 * V1 has only one entry in the catalog (MiniLM-L6-v2 INT8) but the
 * machinery scales when more variants land.
 */
class NluModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = NluModelStorage(app)
    private val downloader = ResumableDownloader()

    private val _models = MutableStateFlow(snapshot())
    val models: StateFlow<List<NluModelUiState>> = _models

    private val downloadJobs = ConcurrentHashMap<String, Job>()

    fun refresh() {
        _models.value = snapshot()
    }

    fun download(modelId: String) {
        if (downloadJobs[modelId]?.isActive == true) return
        val info = NluModelCatalog.byId(modelId) ?: return

        updateModel(modelId) { it.copy(progress = 0f, errorMessage = null) }
        val job = viewModelScope.launch {
            downloader.download(info.url, storage.modelFile(info.id)).collect { event ->
                when (event) {
                    is DownloadStreamEvent.Progress -> {
                        val pct = event.totalBytes
                            ?.takeIf { it > 0L }
                            ?.let { event.bytesDownloaded.toFloat() / it.toFloat() }
                        updateModel(modelId) { it.copy(progress = pct) }
                    }
                    DownloadStreamEvent.Complete -> {
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
                    is DownloadStreamEvent.Failed -> {
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
        // No native handle to evict for NLU yet — Phase 2 will add a
        // companion `evictIntentClassifier(path)` mirroring the Whisper
        // recognizer cache. For Phase 1 the file delete is sufficient.
        storage.delete(modelId)
        refresh()
    }

    fun setActive(modelId: String) {
        if (!storage.isDownloaded(modelId)) {
            Log.w(TAG, "setActive($modelId) ignored — not downloaded")
            return
        }
        Log.d(TAG, "setActive: $modelId")
        storage.activeModelId = modelId
        refresh()
    }

    private fun updateModel(modelId: String, transform: (NluModelUiState) -> NluModelUiState) {
        _models.value = _models.value.map { if (it.info.id == modelId) transform(it) else it }
    }

    private fun snapshot(): List<NluModelUiState> {
        val active = storage.activeModelId
        return NluModelCatalog.all.map { info ->
            NluModelUiState(
                info = info,
                isDownloaded = storage.isDownloaded(info.id),
                isActive = info.id == active,
            )
        }
    }
}
