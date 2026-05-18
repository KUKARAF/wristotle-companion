package com.lazydevs.wristotle.ui

import android.app.Application
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.speech.whisper.ModelCatalog
import com.lazydevs.wristotle.speech.whisper.ModelInfo
import com.lazydevs.wristotle.speech.whisper.ModelStorage

class WhisperModelsViewModel(app: Application) : ModelsViewModel<ModelInfo>(app) {
    override val tag = "WhisperModelsViewModel"
    override val storage = ModelStorage(app)
    override val catalog = ModelCatalog.all
    override fun idOf(info: ModelInfo) = info.id
    override fun urlOf(info: ModelInfo) = info.url

    init { refresh() }

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
