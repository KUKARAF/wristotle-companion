package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.nlu.NluSettings
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
import com.lazydevs.wristotle.speech.nlu.bank.ExampleBank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the "Intent learning" card on the Settings screen. Exposes the
 * single user-facing setting (learn-from-commands toggle) plus the
 * destructive "Clear learned examples" action.
 *
 * Reads through to the Application's [NluSettings] + [ExampleBank] so
 * state is shared with whatever else is reading them
 * (PebbleListenerService, LearningCollector).
 */
class NluSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as WristotleApplication
    private val settings: NluSettings = application.nluSettings
    private val bank: ExampleBank = application.nluBank

    val learningEnabled: StateFlow<Boolean> = settings.learningEnabled

    fun setLearningEnabled(enabled: Boolean) {
        settings.setLearningEnabled(enabled)
    }

    /** Wipes every `source = "learned"` row and rebuilds centroids from seeds. */
    fun clearLearned() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { bank.deleteAllLearned() }
            // Rebuild the classifier so the in-memory bank also drops learned
            // examples — otherwise the next classify() still sees stale rows
            // until the process restarts.
            (IntentClassifiers.provider(application) as? IntentClassifier)?.let {
                runCatching { it.rebuild() }
            }
        }
    }
}
