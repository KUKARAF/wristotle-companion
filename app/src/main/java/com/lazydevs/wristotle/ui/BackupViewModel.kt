package com.lazydevs.wristotle.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.backup.AudioInventory
import com.lazydevs.wristotle.backup.BackupExportResult
import com.lazydevs.wristotle.backup.BackupExporter
import com.lazydevs.wristotle.backup.BackupImportResult
import com.lazydevs.wristotle.backup.BackupImporter
import com.lazydevs.wristotle.backup.BackupManifest
import com.lazydevs.wristotle.backup.PeekResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BackupViewModel"

/**
 * UI state + one-shot event surface for the backup/restore card.
 *
 * Two flows live here:
 *  - **Export.** Capture options → SAF → run [BackupExporter]. State: [isExporting].
 *  - **Restore.** SAF → peek (optionally with password retry) → preview →
 *    run [BackupImporter]. State: [restore] is a small state machine.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val wristotle: WristotleApplication = app as WristotleApplication
    private val exporter = BackupExporter(wristotle)
    private val importer = BackupImporter(wristotle)

    // ── Export ────────────────────────────────────────────────────────────

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _audioInventory = MutableStateFlow(AudioInventory(0, 0))
    val audioInventory: StateFlow<AudioInventory> = _audioInventory

    /** Terminal export-flow state. Drives the export result dialog. */
    private val _exportResult = MutableStateFlow<ExportResultState>(ExportResultState.Idle)
    val exportResult: StateFlow<ExportResultState> = _exportResult

    @Volatile private var pendingOptions: ExportOptions = ExportOptions()

    fun suggestedFilename(now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "wristotle-backup-$ts.zip"
    }

    fun refreshAudioInventory() {
        viewModelScope.launch {
            _audioInventory.value = exporter.audioInventory()
        }
    }

    fun setPendingOptions(includeAudio: Boolean, password: String) {
        pendingOptions = ExportOptions(includeAudio = includeAudio, password = password)
    }

    fun export(destination: Uri) {
        if (_isExporting.value) return
        val opts = pendingOptions
        pendingOptions = ExportOptions()
        viewModelScope.launch {
            _isExporting.value = true
            _exportResult.value = try {
                val result = exporter.export(
                    destination = destination,
                    includeAudio = opts.includeAudio,
                    password = opts.password.takeIf { it.isNotEmpty() },
                )
                ExportResultState.Success(result)
            } catch (t: Throwable) {
                Log.w(TAG, "backup export failed", t)
                ExportResultState.Failure(t.message ?: "export failed")
            } finally {
                _isExporting.value = false
            }
        }
    }

    /** User tapped OK on the export result dialog. */
    fun dismissExportResult() {
        _exportResult.value = ExportResultState.Idle
    }

    // ── Restore ───────────────────────────────────────────────────────────

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore

    /** Entered the restore flow with a freshly-picked URI. */
    fun beginRestore(source: Uri) {
        _restore.value = RestoreState.Loading
        viewModelScope.launch { runPeek(source, password = null, retryAfterBadPassword = false) }
    }

    /** User submitted the password from the prompt; re-peek. */
    fun submitRestorePassword(password: String) {
        val current = _restore.value
        if (current !is RestoreState.NeedsPassword) return
        _restore.value = RestoreState.Loading
        viewModelScope.launch { runPeek(current.uri, password, retryAfterBadPassword = true) }
    }

    /** User confirmed the preview — run the actual import. */
    fun confirmRestore() {
        val current = _restore.value
        if (current !is RestoreState.Preview) return
        _restore.value = RestoreState.Loading
        viewModelScope.launch {
            _restore.value = try {
                val result = importer.import(current.uri, current.password, current.manifest)
                RestoreState.Success(result)
            } catch (t: Throwable) {
                Log.w(TAG, "backup import failed", t)
                RestoreState.Failure(t.message ?: "import failed")
            }
        }
    }

    /** User dismissed any restore dialog (cancel button, OK on result, etc). */
    fun cancelRestore() {
        _restore.value = RestoreState.Idle
    }

    private suspend fun runPeek(source: Uri, password: String?, retryAfterBadPassword: Boolean) {
        when (val r = importer.peek(source, password)) {
            is PeekResult.Ok -> _restore.value = RestoreState.Preview(source, password, r.manifest)
            PeekResult.NeedsPassword -> _restore.value = RestoreState.NeedsPassword(source, wrongTried = false)
            PeekResult.WrongPassword -> _restore.value = RestoreState.NeedsPassword(source, wrongTried = true)
            is PeekResult.Error -> _restore.value = RestoreState.Failure(r.message)
        }
    }
}

/** Two captures from the export-options dialog. */
private data class ExportOptions(
    val includeAudio: Boolean = false,
    val password: String = "",
)

/** Restore flow state — drives which dialog (if any) the Card shows. */
sealed interface RestoreState {
    object Idle : RestoreState
    object Loading : RestoreState
    data class NeedsPassword(val uri: Uri, val wrongTried: Boolean) : RestoreState
    data class Preview(val uri: Uri, val password: String?, val manifest: BackupManifest) : RestoreState
    /** Terminal — shown as the result dialog. User taps OK → Idle. */
    data class Success(val result: BackupImportResult) : RestoreState
    /** Terminal — shown as the result dialog. User taps OK → Idle. */
    data class Failure(val message: String) : RestoreState
}

/** Terminal export state — drives the export result dialog. */
sealed interface ExportResultState {
    object Idle : ExportResultState
    data class Success(val result: BackupExportResult) : ExportResultState
    data class Failure(val message: String) : ExportResultState
}
