// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.apps.InstalledApp
import com.lazydevs.wristotle.handlers.MediaPlayHandler
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.speech.nlu.settings.ConversationSettings
import com.lazydevs.wristotle.speech.nlu.slots.MediaPlaySlots
import com.lazydevs.wristotle.speech.nlu.slots.OpenAppSlots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Conversation + Settings screens. Reads from the
 * [com.lazydevs.wristotle.history.ConversationRepository] +
 * [ConversationSettings] owned by [WristotleApplication]; no own state.
 */
class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as WristotleApplication
    private val repository = application.conversationRepository
    private val settings = application.conversationSettings
    private val audioSettings = application.conversationAudioSettings
    private val audioStore = application.conversationAudioStore
    private val companionDetector = application.pebbleCompanionDetector
    private val aliasStore = application.aliasStore
    private val appIndex = application.appIndex
    private val openAppSlots = OpenAppSlots()
    private val mediaPlaySlots = MediaPlaySlots()

    /** Companion state — drives the dismissable rePebble first-run notice
     *  at the top of the conversation list. */
    val pebbleCompanion: StateFlow<com.lazydevs.wristotle.transport.PebbleCompanionDetector.State> =
        companionDetector.state

    /** Conversation-tab specific dismissal flag. Independent of the
     *  same notice's state in Settings → Models → Speech and in
     *  Permissions → Voice Input. */
    val rePebbleNoticeDismissed: StateFlow<Boolean> =
        companionDetector.conversationNoticeDismissed

    fun dismissRePebbleNotice() = companionDetector.dismissConversationNotice()

    val entries: StateFlow<List<ConversationEntry>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val retentionDays: StateFlow<Int> = settings.retentionDays

    val retentionOptions: List<Int> = ConversationSettings.ALLOWED_RETENTION_DAYS

    /** Whether new dictations are saved as .wav for inline replay. */
    val audioCaptureEnabled: StateFlow<Boolean> = audioSettings.captureEnabled

    /**
     * Flip the audio-capture toggle. When turning off, also wipe any
     * existing recordings so the user's intent ("stop saving my audio")
     * is honoured immediately rather than waiting for the next prune.
     */
    fun setAudioCaptureEnabled(enabled: Boolean) {
        audioSettings.setCaptureEnabled(enabled)
        if (!enabled) deleteAllAudio()
    }

    /**
     * Wipe every saved recording without touching the capture toggle.
     * Past conversation rows lose their play button (the existence check
     * in [ConversationScreen] hides it once the file is gone).
     */
    fun deleteAllAudio() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            audioStore.deleteAll()
        }
    }

    /** Persist the new retention choice and immediately prune so a shorter
     *  window applies right away instead of waiting for the next insert.
     *  Caller is responsible for confirming a destructive (shrinking) change
     *  before invoking this — see [countOlderThan]. */
    fun setRetentionDays(days: Int) {
        settings.setRetentionDays(days)
        viewModelScope.launch { repository.prune() }
    }

    /** How many entries would be deleted if retention were set to [days].
     *  Suspended off the main thread by Room. */
    suspend fun countOlderThan(days: Int): Int = repository.countOlderThan(days)

    fun clearAll() = viewModelScope.launch { repository.clearAll() }

    /**
     * In-flight quick-add-alias draft, or null when the dialog is closed.
     * Seeded from a misheard "open X" row: [suggestedPhrase] is the spoken
     * app token (the same string [AppIndex] failed to resolve), [apps] backs
     * the target picker. The user confirms which app the phrase should open.
     */
    data class AliasDraft(val suggestedPhrase: String, val apps: List<InstalledApp>)

    private val _aliasDraft = MutableStateFlow<AliasDraft?>(null)
    val aliasDraft: StateFlow<AliasDraft?> = _aliasDraft

    /**
     * Open the quick-add-alias dialog seeded from an app-launching row.
     * "play X" and "open X" both resolve the spoken name through the same
     * alias-aware [AppIndex] lookup, so each can be pinned — extract the
     * `app` token with the matching slot extractor to seed the phrase.
     */
    fun beginAlias(entry: ConversationEntry) {
        viewModelScope.launch {
            val extractor = if (entry.handler == MediaPlayHandler.TAG) mediaPlaySlots else openAppSlots
            val phrase = (extractor.extract(entry.userQuery)["app"] as? String).orEmpty()
            _aliasDraft.value = AliasDraft(phrase, appIndex.installedApps())
        }
    }

    fun cancelAlias() { _aliasDraft.value = null }

    fun addAlias(phrase: String, packageId: String) {
        aliasStore.put(phrase, packageId)
        _aliasDraft.value = null
    }
}