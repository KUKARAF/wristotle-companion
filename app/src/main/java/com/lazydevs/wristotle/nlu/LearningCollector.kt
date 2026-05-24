package com.lazydevs.wristotle.nlu

import android.util.Log
import com.lazydevs.wristotle.speech.nlu.Intent
import com.lazydevs.wristotle.speech.nlu.IntentClassifier
import com.lazydevs.wristotle.speech.nlu.bank.ExampleBank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "LearningCollector"

/**
 * Implicit learning loop: when a dispatch succeeds, the (query → intent)
 * pair becomes a positive example for the embedding classifier.
 *
 * Filters in this order:
 *   - settings.learningEnabled (master switch)
 *   - dispatch was successful (handler returned a non-failure string)
 *   - intent is in the learnable whitelist (skip Unknown/error/Time/etc.)
 *   - query passes basic sanity (has letters, under length cap)
 *
 * Inserts dedup at the DAO layer via the unique index on normalizedText;
 * duplicates bump the usageCount instead of inserting. After any new
 * row, schedule a debounced [IntentClassifier.rebuild] so a burst of
 * corrections collapses to one re-embedding pass.
 */
class LearningCollector(
    private val scope: CoroutineScope,
    private val bank: ExampleBank,
    private val classifierProvider: () -> IntentClassifier?,
    private val settings: NluSettings,
) {
    private val mutex = Mutex()
    private var pendingRebuild: Job? = null

    suspend fun record(rawText: String, intent: Intent) {
        if (!settings.learningEnabled.value) return
        if (intent !in LEARNABLE_INTENTS) return
        if (!rawText.any { it.isLetter() }) return

        val inserted = runCatching { bank.addLearned(rawText, intent) }
            .onFailure { Log.w(TAG, "addLearned failed", it) }
            .getOrDefault(false)

        if (inserted) {
            Log.d(TAG, "learned: \"$rawText\" → $intent")
            scheduleRebuild()
        }
    }

    /** Coalesces multiple inserts into one rebuild within [REBUILD_DEBOUNCE_MS]. */
    private suspend fun scheduleRebuild() = mutex.withLock {
        pendingRebuild?.cancel()
        pendingRebuild = scope.launch {
            delay(REBUILD_DEBOUNCE_MS)
            classifierProvider()?.let {
                runCatching { it.rebuild() }
                    .onFailure { e -> Log.w(TAG, "rebuild failed", e) }
            }
        }
    }

    companion object {
        private val LEARNABLE_INTENTS = setOf(
            Intent.Call, Intent.SendMessage, Intent.Reminder, Intent.Cancel, Intent.FindPhone,
        )
        private const val REBUILD_DEBOUNCE_MS = 500L
    }
}
