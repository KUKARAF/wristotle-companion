// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.nlu.NluSettings
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.IntentClassifiers
import com.lazydevs.wristotle.nlu.learning.ExampleBank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One row in the "Saved learned phrases" dialog. `intent` is the raw
 * enum name (`"Call"` / `"SendMessage"` / …); the UI layer maps it to
 * a human-readable label.
 */
data class LearnedExampleRow(
    val id: Long,
    val intent: String,
    val rawText: String,
    val usageCount: Int,
)

/**
 * Backs the "Intent learning" card on the Settings screen. Exposes the
 * learn-from-commands toggle, the destructive "Clear learned examples"
 * action, the read-out of currently-learned phrases (so the user can
 * see what got stored), and per-row delete.
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

    private val _learnedExamples = MutableStateFlow<List<LearnedExampleRow>>(emptyList())
    /** Current learned-phrase set, sorted by intent then by recency. The
     *  card's LaunchedEffect refreshes on (re)entry — the bank isn't
     *  observable so the dialog could otherwise show stale rows. */
    val learnedExamples: StateFlow<List<LearnedExampleRow>> = _learnedExamples

    init { refresh() }

    fun setLearningEnabled(enabled: Boolean) {
        settings.setLearningEnabled(enabled)
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = runCatching { bank.learnedExamples() }
                .getOrDefault(emptyList())
                .sortedWith(compareBy({ it.intent }, { -it.addedAtEpochMs }))
                .map {
                    LearnedExampleRow(
                        id = it.id,
                        intent = it.intent,
                        rawText = it.rawText,
                        usageCount = it.usageCount,
                    )
                }
            _learnedExamples.value = rows
        }
    }

    /** Delete a single learned phrase + rebuild centroids so the
     *  in-memory bank drops it immediately. Same pattern as
     *  [clearLearned] but for one row. */
    fun deleteLearned(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { bank.deleteLearnedById(id) }
            (IntentClassifiers.provider(application) as? IntentClassifier)?.let {
                runCatching { it.rebuild() }
            }
            refresh()
        }
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
            refresh()
        }
    }
}