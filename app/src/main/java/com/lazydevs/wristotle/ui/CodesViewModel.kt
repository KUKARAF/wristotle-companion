// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.speech.nlu.codes.CodeFormat
import com.lazydevs.wristotle.speech.nlu.codes.CodeRepository
import com.lazydevs.wristotle.speech.nlu.codes.SavedCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the saved-codes screen — CRUD over [CodeRepository]; the list re-emits
 *  from Room automatically after each write. */
class CodesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: CodeRepository = (app as WristotleApplication).codeRepository

    val codes: StateFlow<List<SavedCode>> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(label: String, format: CodeFormat, data: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.add(label.trim().ifEmpty { data }, format, data)
        }
    }

    fun rename(id: String, label: String) {
        val l = label.trim()
        if (l.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { repository.updateLabel(id, l) }
    }

    fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) { repository.delete(id) }
}
