// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.speech.nlu.settings.AppendAudioMode
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettings
import com.lazydevs.wristotle.speech.Recognizers
import com.lazydevs.wristotle.speech.audio.MicAudioSource
import com.lazydevs.wristotle.speech.recognizer.Recognizer
import com.lazydevs.wristotle.speech.recognizer.TranscriptionEvent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

    private val notesServer = (app as WristotleApplication).notesServerSync

    /** Signed in to notes.osmosis.page — the list mirrors the server then. */
    val serverConnected: StateFlow<Boolean> =
        (app as WristotleApplication).notesServerAuth.account.map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Pull from the notes server when the screen opens (no-op when signed out). */
    fun refreshFromServer() = viewModelScope.launch {
        notesServer.syncIfStale(maxAgeMs = 10_000, timeoutMs = 30_000)
    }

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

    // -------- Add-note draft (Phase B: companion-side create) --------

    /** Draft body for the new-note sheet. Typing updates this directly;
     *  dictation overwrites it (partial → live update, final → committed). */
    private val _draftBody = MutableStateFlow("")
    val draftBody: StateFlow<String> = _draftBody

    enum class DictationState { IDLE, RECORDING, TRANSCRIBING }
    private val _dictationState = MutableStateFlow(DictationState.IDLE)
    val dictationState: StateFlow<DictationState> = _dictationState

    private val _dictationError = MutableStateFlow<String?>(null)
    val dictationError: StateFlow<String?> = _dictationError

    /** True when a Whisper model is downloaded + active. The Mic button is
     *  gated on this — otherwise the recognizer falls back to a stub and the
     *  transcript would be useless. Read on each invocation; a model
     *  downloaded while the sheet is open isn't picked up until reopen. */
    fun isWhisperModelActive(): Boolean =
        (getApplication<Application>() as WristotleApplication)
            .modelStorage.activeModelPath() != null

    /**
     * Snapshot of the active dictation — recognizer + mic source + the
     * job collecting their output + the text the user typed BEFORE
     * tapping Mic (so dictation appends instead of clobbering).
     *
     * Wrapped in one @Volatile reference so a stop/cancel on Main never
     * sees a partially-updated tuple (e.g. source-set / recognizer-not-yet).
     * Cleared to null when idle. Replaced — never field-mutated — so
     * the JMM guarantee covers all fields at once.
     */
    private data class DictationSession(
        val job: Job,
        val source: MicAudioSource,
        val recognizer: Recognizer,
        val preText: String,
    )
    @Volatile private var session: DictationSession? = null
    /** Audio path published by the recognizer for the current draft, if
     *  audio capture is enabled. Outlives the active dictation — set on
     *  the Final event, consumed when the user saves. @Volatile because
     *  it's written from the IO collect lambda and read from [saveDraft]
     *  on Main. */
    @Volatile private var draftAudioPath: String? = null

    fun setDraftBody(value: String) { _draftBody.value = value }

    fun clearDictationError() { _dictationError.value = null }

    /**
     * Start mic-driven dictation into the draft body. Requires
     * [Manifest.permission.RECORD_AUDIO]; if not granted, sets
     * [dictationError] and stays in IDLE.
     */
    fun startDictation() {
        if (_dictationState.value != DictationState.IDLE) return
        val app = getApplication<Application>() as WristotleApplication
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _dictationError.value = "Microphone permission not granted"
            return
        }
        if (!isWhisperModelActive()) {
            _dictationError.value = "Download a Whisper model in Settings → Models first"
            return
        }
        // Pre-clear the recognizer's last-captured slot so we attribute the
        // .wav this dictation produces to THIS draft (and not a stale watch
        // dictation that happened to publish a path moments ago).
        app.lastCapturedAudioPath = null
        val recognizer = Recognizers.provider(app)
        val source = MicAudioSource()
        // Preserve any text the user typed BEFORE tapping mic — dictation
        // appends to it instead of clobbering. Trailing space added when
        // there's an existing prefix so the joined result reads naturally.
        val preText = _draftBody.value
        _dictationState.value = DictationState.RECORDING

        // IMPORTANT: collect on IO. The recognizer's flow runs whisper.cpp
        // inference inline on the collecting dispatcher (~1–2s blocking JNI);
        // collecting on Main (the default for viewModelScope) freezes the UI
        // for the full inference duration. The watch path doesn't hit this
        // because the system RecognitionService collects on a worker thread.
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                recognizer.transcribe(source).collect { event ->
                    when (event) {
                        is TranscriptionEvent.Partial -> _draftBody.value = joinDictation(event.text)
                        is TranscriptionEvent.Final -> {
                            _draftBody.value = joinDictation(event.text)
                            draftAudioPath = app.lastCapturedAudioPath
                            app.lastCapturedAudioPath = null
                        }
                        is TranscriptionEvent.Error -> {
                            _dictationError.value = "Dictation failed (${event.code})"
                        }
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "dictation flow ended exceptionally", t)
            } finally {
                _dictationState.value = DictationState.IDLE
                session = null
            }
        }
        session = DictationSession(job = job, source = source, recognizer = recognizer, preText = preText)
    }

    /** Stop the mic; the recognizer drains the captured PCM and emits Final. */
    fun stopDictation() {
        if (_dictationState.value != DictationState.RECORDING) return
        _dictationState.value = DictationState.TRANSCRIBING
        // Stopping the source makes the recognizer's samples Flow complete;
        // the recognizer then runs final inference and emits Final.
        runCatching { session?.source?.stop() }
            .onFailure { Log.w(TAG, "source.stop failed", it) }
    }

    /** Persist the current draft as a new Note and reset draft state. */
    fun saveDraft() {
        val body = _draftBody.value.trim()
        if (body.isBlank()) return
        val audioPath = draftAudioPath
        val now = System.currentTimeMillis()
        // Off-main so the audio-file copy doesn't briefly jank the dismiss anim.
        viewModelScope.launch(Dispatchers.IO) {
            val src = if (audioPath != null) SOURCE_VOICE else SOURCE_TYPED
            val id = repository.insert(body = body, source = src, createdAtEpochMs = now)
            if (audioPath != null) repository.attachAudio(id, File(audioPath))
        }
        resetDraft()
    }

    /** Discard the in-progress draft (called on sheet dismiss + after save). */
    fun cancelDraft() {
        val s = session
        s?.job?.cancel()
        runCatching { s?.source?.stop() }
        s?.recognizer?.requestAbort()
        resetDraft()
    }

    private fun joinDictation(transcript: String): String {
        val prefix = session?.preText.orEmpty()
        return if (prefix.isBlank()) transcript else "$prefix $transcript"
    }

    private fun resetDraft() {
        _draftBody.value = ""
        _dictationState.value = DictationState.IDLE
        _dictationError.value = null
        draftAudioPath = null
        session = null
    }

    override fun onCleared() {
        cancelDraft()
        super.onCleared()
    }

    private companion object {
        const val TAG = "NotesViewModel"
        const val SOURCE_TYPED = "companion_typed"
        const val SOURCE_VOICE = "companion_voice"
    }
}