// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.diagnostics

import com.lazydevs.wristotle.speech.nlu.settings.DiagnosticsSettings
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
import com.lazydevs.wristotle.transport.WatchInfoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        val logDump = WristotleLog.dumpRecent(LOG_LINES).let { if (redact) DiagnosticsText.redactDigits(it) else it }

        val markdown = buildString {
            appendIssueTemplate()
            appendLine("---")
            appendLine()
            appendLine("## Diagnostics")
            appendLine()
            appendAppSection()
            appendDeviceSection()
            appendPebbleCompanionSection()
            appendWatchSection()
            appendPermissionsSection()
            appendModelsSection()
            appendSttProviderSection(redact)
            appendTtsProviderSection(redact)
            appendAskAgentSection()
            appendMcpServersSection(redact)
            appendWatchSettingsSection()
            appendWatchCardsSection()
            appendMorningBriefSection()
            appendAppIndexSection()
            appendConversationAudioSection(redact)
            appendConversationSection(recentEntries, redact)
            if (audioFiles.isNotEmpty()) appendAudioSection(audioFiles)
            appendCrashSection(redact)
            appendLogSection(logDump)
        }
        DiagnosticsBundle(markdown, audioFiles)
    }

    // ── Sections ────────────────────────────────────────────────────

    /** Which watch cards are turned off (non-secret UI state). */
    private fun StringBuilder.appendWatchCardsSection() {
        val disabled = app.cardSettings.disabledKinds.value
        appendLine("### Watch cards")
        appendLine(
            "- Disabled kinds: " +
                if (disabled.isEmpty()) "(none — all on)" else disabled.sorted().joinToString(", "),
        )
        appendLine()
    }

    private fun StringBuilder.appendMorningBriefSection() {
        val disabled = app.briefSettings.disabledSections.value
        appendLine("### Morning Brief")
        appendLine(
            "- Disabled sections: " +
                if (disabled.isEmpty()) "(none — all on)" else disabled.sorted().joinToString(", "),
        )
        appendLine()
    }

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
        // Low-RAM devices skip on-device NLU + warm-up entirely (see
        // WristotleApplication.isLowRamDevice gating), so "intents don't work"
        // reports on older phones are explained by this line alone.
        appendLine("- RAM: ${totalRamGb()} GB${if (isLowRamDevice()) " — low-RAM device, NLU disabled" else ""}")
        appendLine()
    }

    private fun StringBuilder.appendPermissionsSection() {
        appendLine("### Permissions")
        appendLine("- Contacts: ${grant(Manifest.permission.READ_CONTACTS)}")
        appendLine("- Phone: ${grant(Manifest.permission.CALL_PHONE)}")
        appendLine("- SMS: ${grant(Manifest.permission.SEND_SMS)}")
        appendLine("- Record audio: ${grant(Manifest.permission.RECORD_AUDIO)}")
        appendLine("- Notification access: ${yesNo(hasNotificationAccess())}")
        // Top cause of "stops working when my phone sleeps" — the whole app is
        // a foreground service; without the exemption the OS can freeze it.
        appendLine("- Battery optimization exempt: ${yesNo(isIgnoringBatteryOptimizations())}")
        // Persistent reminders + alarms use exact alarms; on Android 12+ this
        // can be revoked separately, which makes them fire late or not at all.
        appendLine("- Exact alarms allowed: ${yesNo(canScheduleExactAlarms())}")
        appendLine("- Default voice provider: ${yesNo(isDefaultVoiceProvider())}")
        appendVoiceProviderDetail()
        appendLine()
    }

    /**
     * Raw + parsed view of the voice-provider detection comparison.
     * Surfaced so we can diagnose codeberg #7's "ADB sets it but the
     * card reads not-default" path: dumping both sides lets us see
     * whether `Settings.Secure.voice_recognition_service` even
     * contains a value, and whether the parse + equality reflects
     * what we expect.
     *
     * Indented two spaces under the "Default voice provider" line.
     */
    private fun StringBuilder.appendVoiceProviderDetail() {
        val raw: String? = android.provider.Settings.Secure
            .getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
        val parsed: android.content.ComponentName? = raw?.let {
            android.content.ComponentName.unflattenFromString(it)
        }
        val expected = android.content.ComponentName(
            context,
            com.lazydevs.wristotle.speech.service.WhisperRecognitionService::class.java,
        )
        appendLine("  - Setting raw:    ${raw ?: "(null)"}")
        appendLine("  - Parsed pkg:     ${parsed?.packageName ?: "(unparsable)"}")
        appendLine("  - Parsed class:   ${parsed?.className ?: "(unparsable)"}")
        appendLine("  - Expected pkg:   ${expected.packageName}")
        appendLine("  - Expected class: ${expected.className}")
        appendLine("  - Expected flat:  ${expected.flattenToString()}")
        appendLine("  - Pkg match:      ${yesNo(parsed?.packageName == expected.packageName)}")
        appendLine("  - Class match:    ${yesNo(parsed?.className == expected.className)}")
    }

    private fun StringBuilder.appendPebbleCompanionSection() {
        appendLine("### Pebble companion")
        val state = app.pebbleCompanionDetector.state.value
        appendLine("- Active: ${state.active}")
        appendLine("- microPebble installed: ${yesNo(state.installed.micropebble)}")
        companionVersion(MICROPEBBLE_PKG)?.let { appendLine("  - microPebble version: $it") }
        appendLine("- Core Devices / rePebble installed: ${yesNo(state.installed.repebble)}")
        // The companion app handles dictation under Core Devices, so its
        // version is the single most useful field for "dictation broke after
        // an update" reports (e.g. the local-STT regression in the 1.3.x line).
        companionVersion(CORE_DEVICES_PKG)?.let { appendLine("  - Core Devices app version: $it") }
        appendLine("- Whisper applies to watch dictation: ${yesNo(state.whisperAppliesToWatchDictation)}")
        appendLine()
    }

    /**
     * The connected Pebble watch itself: model + firmware from PebbleKit2 (the
     * paired companion knows these whenever a watch is connected), plus the
     * Wristotle WATCH-app version it last reported on launch (cached, since a
     * watchapp only runs when open). All three were invisible to bug reports
     * before — and the watch model/platform is exactly what pinned down #17.
     */
    private suspend fun StringBuilder.appendWatchSection() {
        appendLine("### Watch")
        val watch = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(2_000) {
                io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever(context)
                    .getConnectedWatches()
                    .first()
                    .firstOrNull()
            }
        }.getOrNull()
        if (watch == null) {
            appendLine("- Connected watch: (none reported by the Pebble app)")
        } else {
            appendLine("- Model: ${watch.platform}")
            appendLine("- Name: ${watch.name}")
            val tag = watch.firmwareVersionTag?.let { " $it" } ?: ""
            appendLine(
                "- Firmware: ${watch.firmwareVersionMajor}." +
                    "${watch.firmwareVersionMinor}.${watch.firmwareVersionPatch}$tag",
            )
        }
        val info = WatchInfoStore(context)
        val wv = info.watchAppVersion
        if (wv == null) {
            appendLine("- Wristotle watch app: (not reported — open Wristotle on the watch once)")
        } else {
            appendLine("- Wristotle watch app: $wv (last seen ${agoString(System.currentTimeMillis() - info.watchAppVersionSeenAt)})")
        }
        appendLine()
    }

    private fun StringBuilder.appendModelsSection() {
        appendLine("### Models")
        val whisper = app.modelStorage.activeModelId ?: "(none)"
        val nlu = app.nluModelStorage.activeModelId ?: "(none)"
        val importedWhisper = app.modelStorage.importedIds().size
        appendLine("- Whisper (Speech): $whisper")
        if (importedWhisper > 0) appendLine("- Imported Whisper models: $importedWhisper")
        appendLine("- MiniLM (Intent): $nlu")
        appendLine()
    }

    private fun StringBuilder.appendSttProviderSection(redact: Boolean) {
        appendLine("### Speech provider (STT)")
        val s = app.sttProviderSettings
        appendLine("- Mode: ${s.mode.value}")
        appendLine("- HTTP base URL: ${urlOrSetState(s.httpBaseUrl.value, redact)}")
        appendLine("- HTTP model: ${nonEmpty(s.httpModel.value)}")
        appendLine("- HTTP key: ${setState(s.httpApiKey.value)}")
        appendLine()
    }

    private fun StringBuilder.appendTtsProviderSection(redact: Boolean) {
        appendLine("### Speech provider (TTS, on-watch — Experimental)")
        val t = app.ttsProviderSettings
        val intents = t.intentsEnabled.value
        appendLine("- Master toggle: ${if (t.enabled.value) "on" else "off"}")
        appendLine("- Mode: ${t.mode.value}")
        appendLine("- HTTP base URL: ${urlOrSetState(t.httpBaseUrl.value, redact)}")
        appendLine("- HTTP model: ${nonEmpty(t.httpModel.value)}")
        appendLine("- HTTP voice: ${nonEmpty(t.httpVoice.value)}")
        appendLine("- HTTP key: ${setState(t.httpApiKey.value)}")
        // Count + alphabetical name list — useful for "Speak on watch is on
        // but Reminders don't speak" bug reports. Names aren't PII, no need
        // to redact.
        appendLine("- Speaking intents: ${intents.size}" +
            if (intents.isNotEmpty()) " (${intents.sorted().joinToString(", ")})" else "")
        appendLine()
    }

    private fun StringBuilder.appendAskAgentSection() {
        appendLine("### Ask Agent")
        val s = app.askAgentSettings
        appendLine("- Provider: ${s.provider.value}")
        appendLine("- Anthropic key: ${setState(s.anthropicApiKey.value)}; model: ${nonEmpty(s.anthropicModel.value)}")
        appendLine("- OpenAI-compat key: ${setState(s.openaiApiKey.value)}; model: ${nonEmpty(s.openaiModel.value)}")
        appendLine("- Custom triggers: ${s.customTriggers.value.size}")
        appendLine("- System prompt: ${s.systemPrompt.value.length} chars")
        appendLine("- Response timeout: ${s.responseTimeoutSec.value}s")
        appendLine()
    }

    private suspend fun StringBuilder.appendMcpServersSection(redact: Boolean) {
        appendLine("### MCP servers")
        val all = app.mcpServerRepository.listAll()
        val enabled = all.count { it.enabled }
        appendLine("- Total: ${all.size} (enabled: $enabled)")
        // Server names are user-chosen labels (could be "internal-corp-mcp"
        // or "personal-todos") — redact under PII mode. URLs + keys are
        // never included regardless of mode.
        all.forEachIndexed { i, s ->
            val label = if (redact) "Server #${i + 1}" else s.name
            appendLine("- ${if (s.enabled) "✓" else "✗"} $label")
        }
        appendLine()
    }

    private fun StringBuilder.appendWatchSettingsSection() {
        appendLine("### Watch settings (mirror)")
        val ws = when (val st = app.watchSettingsRepository.state.value) {
            is com.lazydevs.wristotle.settings.WatchSettingsState.Loaded -> st.settings
            is com.lazydevs.wristotle.settings.WatchSettingsState.Stale -> st.last
            else -> null
        }
        if (ws == null) {
            appendLine("- (not yet requested from watch)")
            appendLine()
            return
        }
        appendLine("- Confirm before send: ${yesNo(ws.confirmBeforeSend)}")
        appendLine("- Confirm timeout: ${ws.confirmTimeoutSeconds}s")
        appendLine("- Confirm default action: ${if (ws.confirmDefaultSend) "send" else "cancel"}")
        appendLine("- SELECT short-press: ${buttonActionLabel(ws.selectAction)}")
        appendLine("- UP long-press: ${buttonActionLabel(ws.longPressUpAction)}")
        appendLine("- DOWN long-press: ${buttonActionLabel(ws.longPressDownAction)}")
        appendLine()
    }

    private fun StringBuilder.appendConversationAudioSection(redact: Boolean) {
        appendLine("### Conversation audio")
        val enabled = app.conversationAudioSettings.captureEnabled.value
        val fileCount = app.conversationAudioStore.dir.listFiles { _, name -> name.endsWith(".wav") }?.size ?: 0
        appendLine("- Capture: ${if (enabled) "enabled" else "disabled"}")
        appendLine("- Stored files: $fileCount")
        // Don't surface the file paths even with redact off — full paths
        // include the device username via `/data/user/0/...` on multi-user
        // setups. The audio section below (when the user opts in) shows
        // them, gated by an explicit toggle.
        @Suppress("UNUSED_PARAMETER") redact
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
            val query = if (redact) "<redacted>" else DiagnosticsText.escapeCell(e.userQuery)
            val response = if (redact) "<redacted>" else DiagnosticsText.escapeCell(e.responseText)
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

    /**
     * Stack traces written by [CrashLogStore] from the last process
     * crash(es). Most installs have none; when the user finally hits
     * a crash this is where the trace surfaces in the bug report so
     * they don't have to dig in `filesDir/crashes/` manually.
     */
    private fun StringBuilder.appendCrashSection(redact: Boolean) {
        val files = CrashLogStore.recent(context.filesDir, CRASH_FILES)
        appendLine("### Recent crashes")
        if (files.isEmpty()) {
            appendLine("_(none)_")
            appendLine()
            return
        }
        for (f in files) {
            appendLine("- `${f.name}`")
        }
        appendLine()
        for (f in files) {
            appendLine("#### ${f.name}")
            appendLine()
            appendLine("````")
            val body = runCatching { f.readText() }.getOrElse { "(unreadable: ${it.javaClass.simpleName})" }
            appendLine(if (redact) DiagnosticsText.redactDigits(body) else body)
            appendLine("````")
            appendLine()
        }
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

    /** Shows the URL when redaction is off; "(set)" otherwise. Empty strings
     *  surface as "(empty)" regardless — that's a setup state, not PII. */
    private fun urlOrSetState(url: String, redact: Boolean): String = when {
        url.isEmpty() -> "(empty)"
        redact -> "(set)"
        else -> url
    }

    /** Show "(set)" / "(empty)" — never expose the key. */
    private fun setState(value: String): String = if (value.isEmpty()) "(empty)" else "(set)"

    /** Show the value or "(empty)" — no redaction (used for model names). */
    private fun nonEmpty(value: String): String = if (value.isEmpty()) "(empty)" else value

    /** Map BUTTON_ACTION_* wire ints to their watch-side label so the
     *  report shows "DICTATION" rather than "3". */
    private fun buttonActionLabel(value: Int): String = when (value) {
        com.lazydevs.wristotle.speech.nlu.transport.MessageKeys.BUTTON_ACTION_MENU      -> "MENU"
        com.lazydevs.wristotle.speech.nlu.transport.MessageKeys.BUTTON_ACTION_NOTES     -> "NOTES"
        com.lazydevs.wristotle.speech.nlu.transport.MessageKeys.BUTTON_ACTION_TASKS     -> "TASKS"
        com.lazydevs.wristotle.speech.nlu.transport.MessageKeys.BUTTON_ACTION_DICTATION -> "DICTATION"
        com.lazydevs.wristotle.speech.nlu.transport.MessageKeys.BUTTON_ACTION_ALARMS    -> "ALARMS"
        else -> "UNKNOWN($value)"
    }

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

    /** "versionName (versionCode)" for an installed companion app, or null
     *  if it isn't installed. Used to surface the Pebble companion build. */
    private fun companionVersion(pkg: String): String? = try {
        val pi = context.packageManager.getPackageInfo(pkg, 0)
        "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
    } catch (t: Throwable) {
        null
    }

    private fun isLowRamDevice(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice

    private fun totalRamGb(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "%.1f".format(info.totalMem / 1024.0 / 1024.0 / 1024.0)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** Pre-Android 12 has no separate exact-alarm gate — always allowed. */
    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .canScheduleExactAlarms()
    }

    /** Pipe + newlines break markdown tables — escape inline. */
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
        const val CRASH_FILES = 3
        const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
        const val MICROPEBBLE_PKG = "si.matejdro.micropebble"
        const val CORE_DEVICES_PKG = "coredevices.coreapp"

        val TIME_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.US)
    }
}