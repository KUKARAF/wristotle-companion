package com.lazydevs.wristotle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.apps.AppIndex
import com.lazydevs.wristotle.apps.AppIndexer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Surface state for the "Installed apps" Settings card. Owns a one-shot
 * scan trigger plus the current count + timestamp so the UI can show
 * "73 apps indexed · scanned 2h ago".
 */
data class AppIndexState(
    val count: Int = 0,
    val lastScannedAtMs: Long? = null,
    val isScanning: Boolean = false,
)

class AppIndexViewModel(app: Application) : AndroidViewModel(app) {

    private val indexer: AppIndexer = (app as WristotleApplication).appIndexer
    private val appIndex: AppIndex = (app as WristotleApplication).appIndex

    private val _state = MutableStateFlow(AppIndexState())
    val state: StateFlow<AppIndexState> = _state

    init { refresh() }

    /** Re-reads count + timestamp from the DB; cheap so the screen can hit it on resume. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                count = appIndex.count(),
                lastScannedAtMs = appIndex.latestScanAt(),
            )
        }
    }

    /** Triggered by the user tapping "Scan installed apps". Re-enumerates + replaces the index. */
    fun rescan() {
        if (_state.value.isScanning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanning = true)
            val n = indexer.refresh()
            _state.value = AppIndexState(
                count = n,
                lastScannedAtMs = System.currentTimeMillis(),
                isScanning = false,
            )
        }
    }
}
