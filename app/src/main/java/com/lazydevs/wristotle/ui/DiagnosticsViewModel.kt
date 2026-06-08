// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.diagnostics.DiagnosticsBuilder
import com.lazydevs.wristotle.speech.nlu.settings.DiagnosticsSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val TAG = "DiagnosticsViewModel"
private const val CODEBERG_NEW_ISSUE_URL =
    "https://codeberg.org/wristotle/wristotle-companion/issues/new"

/**
 * UX for the diagnostics card. Owns the two toggles + a one-shot
 * "export" action that:
 *   1. Builds the markdown bundle (DB read + log dump + audio copy).
 *   2. Copies the markdown to the system clipboard.
 *   3. Emits a [UserMessage] event so the card can show a Toast.
 *   4. Lets the Activity open the Codeberg new-issue URL.
 *
 * Building runs in `viewModelScope` on Dispatchers.IO inside the
 * builder; the button is disabled while in flight so the user can't
 * double-tap.
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val wristotle: WristotleApplication = app as WristotleApplication
    private val settings: DiagnosticsSettings = wristotle.diagnosticsSettings
    private val builder = DiagnosticsBuilder(app, wristotle, settings)

    val redactPii: StateFlow<Boolean> = settings.redactPii
    val includeAudio: StateFlow<Boolean> = settings.includeAudio

    private val _isBuilding = MutableStateFlow(false)
    val isBuilding: StateFlow<Boolean> = _isBuilding

    /** Events the Card observes — opens browser + Toast. */
    private val _events = MutableSharedFlow<DiagnosticsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<DiagnosticsEvent> = _events.asSharedFlow()

    fun setRedactPii(enabled: Boolean) = settings.setRedactPii(enabled)
    fun setIncludeAudio(enabled: Boolean) = settings.setIncludeAudio(enabled)

    /**
     * True iff the conversation audio capture is on — without it,
     * the include-audio toggle has nothing to attach so the UI
     * surfaces it as disabled.
     */
    val audioCaptureEnabled: StateFlow<Boolean> = wristotle.conversationAudioSettings.captureEnabled

    fun exportAndOpenIssue() {
        if (_isBuilding.value) return
        viewModelScope.launch {
            _isBuilding.value = true
            try {
                val bundle = builder.build()
                copyToClipboard(getApplication(), bundle.markdown)
                val attachmentNote = when (bundle.audioFiles.size) {
                    0 -> null
                    1 -> "1 audio file saved — attach manually after submitting"
                    else -> "${bundle.audioFiles.size} audio files saved — attach manually after submitting"
                }
                _events.tryEmit(DiagnosticsEvent.ReadyToSubmit(attachmentNote))
            } catch (t: Throwable) {
                Log.w(TAG, "diagnostics export failed", t)
                _events.tryEmit(DiagnosticsEvent.Failed(t.message ?: "export failed"))
            } finally {
                _isBuilding.value = false
            }
        }
    }

    /**
     * Activity-side helper — opens the Codeberg new-issue page in
     * a browser. Kept on the VM so the Activity doesn't need to
     * import the URL constant.
     */
    fun openCodebergNewIssue(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, CODEBERG_NEW_ISSUE_URL.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "no browser to open Codeberg URL", t)
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Wristotle diagnostics", text))
    }
}

/** One-shot signals from the VM the Card consumes via collectAsState. */
sealed interface DiagnosticsEvent {
    data class ReadyToSubmit(val attachmentNote: String?) : DiagnosticsEvent
    data class Failed(val message: String) : DiagnosticsEvent
}