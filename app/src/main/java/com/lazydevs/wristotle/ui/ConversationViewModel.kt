package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.history.ConversationSettings
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

    val entries: StateFlow<List<ConversationEntry>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val retentionDays: StateFlow<Int> = settings.retentionDays

    val retentionOptions: List<Int> = ConversationSettings.ALLOWED_RETENTION_DAYS

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
}
