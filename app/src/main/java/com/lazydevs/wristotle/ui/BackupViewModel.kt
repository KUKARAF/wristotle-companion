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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BackupViewModel"

/**
 * UI state + one-shot event surface for the backup/restore card.
 *
 * Phase B adds the export-options dialog state: the audio-inclusion toggle,
 * the optional password, and the audio inventory (file count + total bytes)
 * shown alongside the toggle.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val wristotle: WristotleApplication = app as WristotleApplication
    private val exporter = BackupExporter(wristotle)

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    /** Lazily refreshed on every dialog open. Defaults to (0, 0) so the UI
     *  has something to render before the first refresh resolves. */
    private val _audioInventory = MutableStateFlow(AudioInventory(0, 0))
    val audioInventory: StateFlow<AudioInventory> = _audioInventory

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BackupEvent> = _events.asSharedFlow()

    /**
     * Options captured from the export-options dialog. The Card writes them
     * here when the user taps Choose file…, then the URI returned by SAF is
     * paired with these to run the export. Cleared after every export.
     */
    @Volatile private var pendingOptions: ExportOptions = ExportOptions()

    /**
     * Default filename suggested when the system Save-As dialog opens.
     * Includes wall-clock so multiple backups don't trample each other.
     */
    fun suggestedFilename(now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "wristotle-backup-$ts.zip"
    }

    /** Refresh the audio inventory shown in the dialog. */
    fun refreshAudioInventory() {
        viewModelScope.launch {
            _audioInventory.value = exporter.audioInventory()
        }
    }

    /**
     * Records the user's dialog selections so they're available when the
     * SAF callback delivers the destination URI. Called immediately before
     * `launcher.launch(filename)`.
     */
    fun setPendingOptions(includeAudio: Boolean, password: String) {
        pendingOptions = ExportOptions(includeAudio = includeAudio, password = password)
    }

    /**
     * Runs the export against the SAF-provided destination, using the most
     * recent [setPendingOptions] selections. The Card calls this after the
     * user picks a file location.
     */
    fun export(destination: Uri) {
        if (_isExporting.value) return
        val opts = pendingOptions
        // Defensive: zero our reference to the password as soon as we hand it
        // to the exporter so the GC has the only retained copy in the
        // exporter's scope, not in this VM field.
        pendingOptions = ExportOptions()
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val result = exporter.export(
                    destination = destination,
                    includeAudio = opts.includeAudio,
                    password = opts.password.takeIf { it.isNotEmpty() },
                )
                _events.tryEmit(BackupEvent.ExportDone(result))
            } catch (t: Throwable) {
                Log.w(TAG, "backup export failed", t)
                _events.tryEmit(BackupEvent.ExportFailed(t.message ?: "export failed"))
            } finally {
                _isExporting.value = false
            }
        }
    }
}

/** Two captures from the export-options dialog held between dialog dismiss
 *  and the SAF URI callback. Keep small + immutable for safety. */
private data class ExportOptions(
    val includeAudio: Boolean = false,
    val password: String = "",
)

/** One-shot signals the Card consumes via collectAsState / LaunchedEffect. */
sealed interface BackupEvent {
    data class ExportDone(val result: BackupExportResult) : BackupEvent
    data class ExportFailed(val message: String) : BackupEvent
}
