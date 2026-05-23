package com.lazydevs.wristotle.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.notes.AppendAudioMode
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.notes.NoteRepository
import com.lazydevs.wristotle.notes.NoteSettings
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

    /** The actively-recording mic + recognizer for this dictation, if any.
     *  Stored so [stopDictation] can drive them. Cleared in the flow's
     *  terminal handler. */
    private var dictationJob: Job? = null
    private var dictationSource: MicAudioSource? = null
    private var dictationRecognizer: Recognizer? = null
    /** Audio path published by the recognizer for the current draft, if
     *  audio capture is enabled. Attached to the new note on save. */
    private var draftAudioPath: String? = null

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
            _dictationError.value = "Download a Whisper model in Settings first"
            return
        }
        // Pre-clear the recognizer's last-captured slot so we attribute the
        // .wav this dictation produces to THIS draft (and not a stale watch
        // dictation that happened to publish a path moments ago).
        app.lastCapturedAudioPath = null
        val recognizer = Recognizers.provider(app)
        val source = MicAudioSource()
        dictationRecognizer = recognizer
        dictationSource = source
        _dictationState.value = DictationState.RECORDING
        _draftBody.value = ""

        // IMPORTANT: collect on IO. The recognizer's flow runs whisper.cpp
        // inference inline on the collecting dispatcher (~1–2s blocking JNI);
        // collecting on Main (the default for viewModelScope) freezes the UI
        // for the full inference duration. The watch path doesn't hit this
        // because the system RecognitionService collects on a worker thread.
        dictationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                recognizer.transcribe(source).collect { event ->
                    when (event) {
                        is TranscriptionEvent.Partial -> _draftBody.value = event.text
                        is TranscriptionEvent.Final -> {
                            _draftBody.value = event.text
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
                dictationRecognizer = null
                dictationSource = null
            }
        }
    }

    /** Stop the mic; the recognizer drains the captured PCM and emits Final. */
    fun stopDictation() {
        if (_dictationState.value != DictationState.RECORDING) return
        _dictationState.value = DictationState.TRANSCRIBING
        // Stopping the source makes the recognizer's samples Flow complete;
        // the recognizer then runs final inference and emits Final.
        runCatching { dictationSource?.stop() }
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
        dictationJob?.cancel()
        runCatching { dictationSource?.stop() }
        dictationRecognizer?.requestAbort()
        resetDraft()
    }

    private fun resetDraft() {
        _draftBody.value = ""
        _dictationState.value = DictationState.IDLE
        _dictationError.value = null
        draftAudioPath = null
        dictationJob = null
        dictationSource = null
        dictationRecognizer = null
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
