package com.lazydevs.wristotle.diagnostics

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import com.lazydevs.wristotle.BuildConfig
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.logging.WristotleLog
import com.lazydevs.wristotle.util.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of building a diagnostics bundle.
 *
 * @property markdown   The body the user pastes into the Codeberg
 *                      issue. Safe to copy to clipboard.
 * @property audioFiles WAV files copied to a stable temp directory
 *                      when the user opted in. The caller surfaces
 *                      their paths so the user can attach manually
 *                      (Codeberg's URL prefill can't carry binary
 *                      attachments).
 */
data class DiagnosticsBundle(
    val markdown: String,
    val audioFiles: List<File>,
)

/**
 * Assembles the markdown report the user pastes into a Codeberg
 * issue. See `DiagnosticsSettings` for the user-facing toggles
 * (redact PII, include audio).
 *
 * Run on `Dispatchers.IO` — does DB reads, file copies, PackageManager
 * lookups. Single-shot; the result is the user's snapshot of "what
 * the app looks like right now".
 */
class DiagnosticsBuilder(
    private val context: Context,
    private val app: WristotleApplication,
    private val settings: DiagnosticsSettings,
) {

    suspend fun build(): DiagnosticsBundle = withContext(Dispatchers.IO) {
        val redact = settings.redactPii.value
        val includeAudio = settings.includeAudio.value && app.conversationAudioSettings.captureEnabled.value

        val recentEntries = app.conversationRepository.recent(RECENT_ENTRIES)
        val audioFiles = if (includeAudio) copyAudioForReport(recentEntries) else emptyList()
        val logDump = WristotleLog.dumpRecent(LOG_LINES).let { if (redact) redactDigits(it) else it }

        val markdown = buildString {
            appendIssueTemplate()
            appendLine("---")
            appendLine()
            appendLine("## Diagnostics")
            appendLine()
            appendAppSection()
            appendDeviceSection()
            appendPermissionsSection()
            appendModelsSection()
            appendAppIndexSection()
            appendConversationSection(recentEntries, redact)
            if (audioFiles.isNotEmpty()) appendAudioSection(audioFiles)
            appendLogSection(logDump)
        }
        DiagnosticsBundle(markdown, audioFiles)
    }

    // ── Sections ────────────────────────────────────────────────────

    private fun StringBuilder.appendIssueTemplate() {
        appendLine("## Issue")
        appendLine()
        appendLine("_Replace this with a short description of what went wrong._")
        appendLine()
        appendLine("**Steps to reproduce:**")
        appendLine("1. …")
        appendLine()
        appendLine("**Expected:** …")
        appendLine()
        appendLine("**Actual:** …")
        appendLine()
    }

    private fun StringBuilder.appendAppSection() {
        appendLine("### App")
        appendLine("- Version: ${BuildConfig.VERSION_NAME} (code ${packageVersionCode()})")
        appendLine("- Build type: ${BuildConfig.BUILD_TYPE}")
        appendLine()
    }

    private fun StringBuilder.appendDeviceSection() {
        appendLine("### Device")
        appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("- Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine()
    }

    private fun StringBuilder.appendPermissionsSection() {
        appendLine("### Permissions")
        appendLine("- Contacts: ${grant(Manifest.permission.READ_CONTACTS)}")
        appendLine("- Phone: ${grant(Manifest.permission.CALL_PHONE)}")
        appendLine("- SMS: ${grant(Manifest.permission.SEND_SMS)}")
        appendLine("- Record audio: ${grant(Manifest.permission.RECORD_AUDIO)}")
        appendLine("- Notification access: ${yesNo(hasNotificationAccess())}")
        appendLine("- Default voice provider: ${yesNo(isDefaultVoiceProvider())}")
        appendLine()
    }

    private fun StringBuilder.appendModelsSection() {
        appendLine("### Models")
        val whisper = app.modelStorage.activeModelId ?: "(none)"
        val nlu = app.nluModelStorage.activeModelId ?: "(none)"
        appendLine("- Whisper (Speech): $whisper")
        appendLine("- MiniLM (Intent): $nlu")
        appendLine()
    }

    private suspend fun StringBuilder.appendAppIndexSection() {
        appendLine("### Installed apps index")
        val count = app.appIndex.count()
        val lastScan = app.appIndex.latestScanAt()
            ?.let { agoString(System.currentTimeMillis() - it) }
            ?: "never"
        appendLine("- Apps indexed: $count")
        appendLine("- Last scan: $lastScan")
        appendLine()
    }

    private fun StringBuilder.appendConversationSection(entries: List<ConversationEntry>, redact: Boolean) {
        appendLine("### Recent conversation (last ${entries.size}${if (redact) ", redacted" else ""})")
        if (entries.isEmpty()) {
            appendLine("_(none)_")
            appendLine()
            return
        }
        appendLine("| time | handler | success | intent (conf) | query | response |")
        appendLine("|---|---|---|---|---|---|")
        for (e in entries) {
            val time = TIME_FORMAT.format(Date(e.timestampEpochMs))
            val intent = e.nluIntent ?: "-"
            val conf = e.nluConfidence?.let { "%.2f".format(it) } ?: "-"
            val query = if (redact) "<redacted>" else escapeCell(e.userQuery)
            val response = if (redact) "<redacted>" else escapeCell(e.responseText)
            appendLine("| $time | ${e.handler} | ${if (e.success) "✓" else "✗"} | $intent ($conf) | $query | $response |")
        }
        appendLine()
    }

    private fun StringBuilder.appendAudioSection(files: List<File>) {
        appendLine("### Audio attachments (${files.size})")
        appendLine("Copied to:")
        for (f in files) appendLine("- `${f.absolutePath}`")
        appendLine()
        appendLine("Attach these to the issue manually — Codeberg's URL prefill can't carry binary files.")
        appendLine()
    }

    private fun StringBuilder.appendLogSection(logDump: String) {
        appendLine("### Recent logs (last $LOG_LINES lines)")
        appendLine()
        // Quadruple-backticks so any nested triple-backtick string in
        // the logs doesn't break the fence.
        appendLine("````")
        if (logDump.isBlank()) appendLine("(empty)") else appendLine(logDump)
        appendLine("````")
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun grant(permission: String): String = yesNo(context.hasPermission(permission))

    private fun yesNo(b: Boolean): String = if (b) "granted" else "denied"

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun isDefaultVoiceProvider(): Boolean {
        val current = android.provider.Settings.Secure
            .getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
            ?.let { android.content.ComponentName.unflattenFromString(it) }
        val expected = android.content.ComponentName(
            context,
            com.lazydevs.wristotle.speech.service.WhisperRecognitionService::class.java,
        )
        return current == expected
    }

    private fun packageVersionCode(): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi)
    } catch (t: Throwable) {
        -1L
    }

    /** Pipe + newlines break markdown tables — escape inline. */
    private fun escapeCell(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").take(80)

    /** Light-touch redaction for log lines — strips any run of 7+ digits
     *  (phone numbers). Contact-name redaction would need to enumerate
     *  contacts; the conversation table already redacts queries fully
     *  when the toggle is on. */
    private fun redactDigits(text: String): String =
        DIGIT_RUN.replace(text, "<digits>")

    private suspend fun copyAudioForReport(entries: List<ConversationEntry>): List<File> {
        val targetDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        // Wipe previous export so the dir doesn't accumulate.
        targetDir.listFiles()?.forEach { it.delete() }
        val timestamp = System.currentTimeMillis()
        return entries.asSequence()
            .mapNotNull { it.audioFilePath?.let(::File)?.takeIf(File::exists) }
            .distinct()
            .take(AUDIO_ATTACHMENTS)
            .mapIndexedNotNull { i, src ->
                val dest = File(targetDir, "dictation-${timestamp}-${i + 1}.wav")
                try {
                    src.copyTo(dest, overwrite = true)
                } catch (_: Throwable) {
                    null
                }
            }
            .toList()
    }

    private fun agoString(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s ago"
            sec < 3600 -> "${sec / 60}m ago"
            sec < 86_400 -> "${sec / 3600}h ago"
            else -> "${sec / 86_400}d ago"
        }
    }

    private companion object {
        const val RECENT_ENTRIES = 10
        const val LOG_LINES = 200
        const val AUDIO_ATTACHMENTS = 3
        const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

        val TIME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.US)
        val DIGIT_RUN = Regex("\\b\\d{7,}\\b")
    }
}
