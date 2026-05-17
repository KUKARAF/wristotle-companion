package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.history.ConversationEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Conversation screen with a live list of every persisted
 * interaction (newest first). Reads directly from the
 * [com.lazydevs.wristotle.history.ConversationRepository] owned by
 * [WristotleApplication]; no own state.
 */
class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as WristotleApplication).conversationRepository

    val entries: StateFlow<List<ConversationEntry>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearAll() = viewModelScope.launch { repository.clearAll() }
}
