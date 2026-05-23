package com.lazydevs.wristotle.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
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
 * Phase A handles export only — Restore wires up in Phase C. The exporter runs
 * on `Dispatchers.IO` inside [BackupExporter.export]; this VM owns the UI's
 * "in flight" flag so the Export button can disable while running.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val wristotle: WristotleApplication = app as WristotleApplication
    private val exporter = BackupExporter(wristotle)

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _events = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BackupEvent> = _events.asSharedFlow()

    /**
     * Default filename suggested when the system Save-As dialog opens.
     * Includes wall-clock so multiple backups don't trample each other.
     */
    fun suggestedFilename(now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "wristotle-backup-$ts.zip"
    }

    /**
     * Runs the export against the SAF-provided destination. The Card calls
     * this after the user picks a file location.
     */
    fun export(destination: Uri) {
        if (_isExporting.value) return
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val result = exporter.export(destination)
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

/** One-shot signals the Card consumes via collectAsState / LaunchedEffect. */
sealed interface BackupEvent {
    data class ExportDone(val result: BackupExportResult) : BackupEvent
    data class ExportFailed(val message: String) : BackupEvent
}
