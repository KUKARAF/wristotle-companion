package com.lazydevs.wristotle.ui

import android.app.Application
import com.lazydevs.wristotle.speech.nlu.model.NluModelCatalog
import com.lazydevs.wristotle.speech.nlu.model.NluModelInfo
import com.lazydevs.wristotle.speech.nlu.model.NluModelStorage

class NluModelsViewModel(app: Application) : ModelsViewModel<NluModelInfo>(app) {
    override val tag = "NluModelsViewModel"
    override val storage = NluModelStorage(app)
    override val catalog = NluModelCatalog.all
    override fun idOf(info: NluModelInfo) = info.id
    override fun urlOf(info: NluModelInfo) = info.url

    init { refresh() }
    // No `onAfterDelete` override yet — the NLU classifier doesn't
    // expose a path-keyed cache to evict. When EmbeddingIntentClassifier
    // grows one (see WristotleApplication's cachedClassifier), add the
    // mirror of WhisperModelsViewModel.onAfterDelete here.
}
