package com.lazydevs.wristotle.ui

import android.app.Application
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.speech.whisper.ModelCatalog
import com.lazydevs.wristotle.speech.whisper.ModelInfo
import com.lazydevs.wristotle.speech.whisper.ModelStorage
import com.lazydevs.wristotle.transport.PebbleCompanionDetector
import kotlinx.coroutines.flow.StateFlow

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
