package com.lazydevs.wristotle.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.backup.AudioInventory
import com.lazydevs.wristotle.backup.BackupCounts
import com.lazydevs.wristotle.backup.BackupExportResult
import com.lazydevs.wristotle.backup.BackupExporter
import com.lazydevs.wristotle.backup.BackupImportResult
import com.lazydevs.wristotle.backup.BackupImporter
import com.lazydevs.wristotle.backup.BackupManifest
import com.lazydevs.wristotle.backup.BackupSelection
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

    /** Per-category counts loaded fresh when the export dialog opens. */
    private val _exportCounts = MutableStateFlow(BackupCounts.EMPTY)
    val exportCounts: StateFlow<BackupCounts> = _exportCounts

    /** Terminal export-flow state. Drives the export result dialog. */
    private val _exportResult = MutableStateFlow<ExportResultState>(ExportResultState.Idle)
    val exportResult: StateFlow<ExportResultState> = _exportResult

    /**
     * User-visible per-category selection for the next export. Observed
     * by `BackupCard`'s checkbox tree; each toggle calls one of the
     * `setExport*` methods to flip a single field.
     */
    private val _exportSelection = MutableStateFlow(BackupSelection())
    val exportSelection: StateFlow<BackupSelection> = _exportSelection

    @Volatile private var pendingPassword: String = ""

    fun suggestedFilename(now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "wristotle-backup-$ts.zip"
    }

    fun refreshAudioInventory() {
        viewModelScope.launch {
            _audioInventory.value = exporter.audioInventory()
        }
    }

    /** Reloads per-category counts. Called when the export dialog opens. */
    fun refreshExportCounts() {
        viewModelScope.launch {
            _exportCounts.value = exporter.countAll()
        }
    }

    fun setExportSelection(value: BackupSelection) {
        _exportSelection.value = value
    }

    fun toggleExportSelectAll() {
        _exportSelection.value =
            if (_exportSelection.value.allSelected) BackupSelection.NONE
            else BackupSelection.ALL
    }

    fun setPendingPassword(password: String) {
        pendingPassword = password
    }

    fun export(destination: Uri) {
        if (_isExporting.value) return
        val selection = _exportSelection.value
        val password = pendingPassword
        pendingPassword = ""
        viewModelScope.launch {
            _isExporting.value = true
            _exportResult.value = try {
                val result = exporter.export(
                    destination = destination,
                    selection = selection,
                    password = password.takeIf { it.isNotEmpty() },
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

    /** User toggled a per-category checkbox on the restore preview. */
    fun setRestoreSelection(value: BackupSelection) {
        val current = _restore.value
        if (current !is RestoreState.Preview) return
        // Categories not in the ZIP can't be restored regardless of
        // what the user ticks — clamp the requested selection by the
        // manifest's `available` so the view always shows truth.
        _restore.value = current.copy(restoreSelection = clamp(value, current.available))
    }

    fun toggleRestoreSelectAll() {
        val current = _restore.value
        if (current !is RestoreState.Preview) return
        val target = if (current.restoreSelection == current.available) BackupSelection.NONE
        else current.available
        _restore.value = current.copy(restoreSelection = target)
    }

    /** User confirmed the preview — run the actual import. */
    fun confirmRestore() {
        val current = _restore.value
        if (current !is RestoreState.Preview) return
        _restore.value = RestoreState.Loading
        viewModelScope.launch {
            _restore.value = try {
                val result = importer.import(
                    source = current.uri,
                    password = current.password,
                    manifest = current.manifest,
                    selection = current.restoreSelection,
                )
                RestoreState.Success(result)
            } catch (t: Throwable) {
                Log.w(TAG, "backup import failed", t)
                RestoreState.Failure(t.message ?: "import failed")
            }
        }
    }

    /** Categories the user can pick from at most — i.e. what's in the ZIP. */
    private fun clamp(requested: BackupSelection, available: BackupSelection) = BackupSelection(
        notes = requested.notes && available.notes,
        tasks = requested.tasks && available.tasks,
        conversations = requested.conversations && available.conversations,
        reminders = requested.reminders && available.reminders,
        nluLearned = requested.nluLearned && available.nluLearned,
        appAliases = requested.appAliases && available.appAliases,
        contactAliases = requested.contactAliases && available.contactAliases,
        audioRecordings = requested.audioRecordings && available.audioRecordings,
        appPreferences = requested.appPreferences && available.appPreferences,
        weatherSettings = requested.weatherSettings && available.weatherSettings,
        mcpServers = requested.mcpServers && available.mcpServers,
        askAgentSetup = requested.askAgentSetup && available.askAgentSetup,
        weatherApiKey = requested.weatherApiKey && available.weatherApiKey,
        mcpAuthHeaders = requested.mcpAuthHeaders && available.mcpAuthHeaders,
        askAgentApiKeys = requested.askAgentApiKeys && available.askAgentApiKeys,
    )

    /** User dismissed any restore dialog (cancel button, OK on result, etc). */
    fun cancelRestore() {
        _restore.value = RestoreState.Idle
    }

    private suspend fun runPeek(source: Uri, password: String?, retryAfterBadPassword: Boolean) {
        when (val r = importer.peek(source, password)) {
            is PeekResult.Ok -> {
                // Pre-tick the restore checkboxes with whatever the ZIP
                // says was selected at export — common case is the user
                // wants to restore everything that's in the file.
                val available = r.manifest.selected
                _restore.value = RestoreState.Preview(
                    uri = source,
                    password = password,
                    manifest = r.manifest,
                    available = available,
                    restoreSelection = available,
                )
            }
            PeekResult.NeedsPassword -> _restore.value = RestoreState.NeedsPassword(source, wrongTried = false)
            PeekResult.WrongPassword -> _restore.value = RestoreState.NeedsPassword(source, wrongTried = true)
            is PeekResult.Error -> _restore.value = RestoreState.Failure(r.message)
        }
    }
}

/** Restore flow state — drives which dialog (if any) the Card shows. */
sealed interface RestoreState {
    object Idle : RestoreState
    object Loading : RestoreState
    data class NeedsPassword(val uri: Uri, val wrongTried: Boolean) : RestoreState
    data class Preview(
        val uri: Uri,
        val password: String?,
        val manifest: BackupManifest,
        /** What's actually in the ZIP (from manifest.selected) — drives
         *  which restore checkboxes are enabled. */
        val available: BackupSelection,
        /** What the user wants restored (subset of [available]). */
        val restoreSelection: BackupSelection,
    ) : RestoreState
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
