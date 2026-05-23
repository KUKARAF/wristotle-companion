package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.notes.AppendAudioMode
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.notes.NoteSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Notes tab. Surfaces:
 *   - the live note list (filtered by [query])
 *   - the keep-last-N cap + presets for the settings card
 *   - delete-one + delete-all operations
 *
 * Notes are user data, so the only destructive operations exposed go through
 * the repository (which deletes the associated `.wav` too).
 */
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: NoteRepository =
        (app as WristotleApplication).noteRepository
    private val settings: NoteSettings =
        (app as WristotleApplication).noteSettings

    /** User-typed search filter; case-insensitive substring match on body. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val notes: StateFlow<List<Note>> =
        combine(repository.observeAll(), _query) { all, q ->
            if (q.isBlank()) all
            else all.filter { it.body.contains(q, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val keepLast: StateFlow<Int> = settings.keepLast

    val keepLastOptions: List<Int> = NoteSettings.ALLOWED_KEEP_LAST

    val appendAudioMode: StateFlow<AppendAudioMode> = settings.appendAudioMode

    fun setAppendAudioMode(mode: AppendAudioMode) = settings.setAppendAudioMode(mode)

    fun setQuery(value: String) { _query.value = value }

    fun setKeepLast(value: Int) {
        settings.setKeepLast(value)
        viewModelScope.launch { repository.prune() }
    }

    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }

    fun deleteAll() = viewModelScope.launch { repository.deleteAll() }
}
