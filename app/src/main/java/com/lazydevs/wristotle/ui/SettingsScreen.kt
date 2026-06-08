// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.handlers.ReminderSettings
import com.lazydevs.wristotle.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * Top-level Settings categories — order here is the listing order on the
 * landing page. Each entry maps to one drill-down sub-screen rendered by
 * [SettingsCategoryContent].
 *
 * `emoji` is rendered via the system emoji font as the [ListItem]
 * leading content. Emoji (not Material icons) intentionally: keeps the
 * landing visually distinct from Material's standard chrome and avoids
 * pulling in `material-icons-extended` symbols just for eight glyphs.
 */
enum class SettingsCategory(@param:StringRes val labelRes: Int, val emoji: String) {
    Setup(R.string.settings_section_setup, "🌟"),
    Watch(R.string.settings_section_watch, "⌚"),
    Conversation(R.string.settings_section_conversation, "💬"),
    Notes(R.string.settings_section_notes, "📝"),
    Reminders(R.string.settings_section_reminders, "⏰"),
    Notifications(R.string.settings_section_notifications, "🔔"),
    Weather(R.string.settings_section_weather, "☁️"),
    Models(R.string.settings_section_models, "🧠"),
    Learning(R.string.settings_section_learning, "🎓"),
    Backup(R.string.settings_section_backup, "💾"),
    Mcp(R.string.settings_section_mcp, "🔌"),
    AskAgent(R.string.settings_section_askagent, "✨"),
    Diagnostics(R.string.settings_section_diagnostics, "🔧"),
    Help(R.string.settings_section_help, "❓"),
    Support(R.string.settings_section_support, "❤️"),
}

/**
 * Settings tab — drill-down navigation. The landing page is a short list
 * of categories; tapping one swaps the body to that category's cards
 * with a back button up top. System back goes back to the landing.
 *
 * Add new categories to [SettingsCategory] and wire them in
 * [SettingsCategoryContent] — the landing list rebuilds from the enum
 * automatically.
 */
@Composable
fun SettingsScreen(
    modelsVm: WhisperModelsViewModel,
    nluModelsVm: NluModelsViewModel,
    nluSettingsVm: NluSettingsViewModel,
    conversationVm: ConversationViewModel,
    appIndexVm: AppIndexViewModel,
    appAliasesVm: AppAliasesViewModel,
    contactAliasesVm: ContactAliasesViewModel,
    notesVm: NotesViewModel,
    diagnosticsVm: DiagnosticsViewModel,
    watchSettingsVm: WatchSettingsViewModel,
    backupVm: BackupViewModel,
    mcpServersVm: McpServersViewModel,
    attentionByCategory: Map<SettingsCategory, Boolean> = emptyMap(),
    /** Non-null when the app launched into a version it hasn't seen
     *  before — drill straight into [SettingsCategory.Help] and have
     *  the card mark + auto-scroll to the matching FeatureEntry, so
     *  the user lands on the entry that documents what they just got
     *  without losing the rest of the page. See
     *  [com.lazydevs.wristotle.help.WhatsNewState]. */
    whatsNewVersion: String? = null,
    /** Called once the auto-open has fired so MainScreen can clear its
     *  one-shot trigger and we don't re-trigger on configuration changes
     *  / process recreates. */
    onWhatsNewConsumed: () -> Unit = {},
    /** Non-null when the welcome wizard's "Open settings" button
     *  triggered navigation here — auto-drill into this category's
     *  sub-screen so the user lands exactly where the wizard told
     *  them they would. Consumed once via [onInitialCategoryConsumed]. */
    initialCategory: SettingsCategory? = null,
    onInitialCategoryConsumed: () -> Unit = {},
    /** Called when a Setup-card "Open" button targets a top-level
     *  bottom-nav tab (today: only [com.lazydevs.wristotle.ui.nav.Screen.Permissions]).
     *  Settings can't switch top-level tabs itself — it bubbles up to
     *  MainScreen which owns the NavController. */
    onOpenTopLevelTab: (com.lazydevs.wristotle.ui.nav.Screen) -> Unit = {},
) {
    // Reminder / Weather / AskAgent settings are app-scoped singletons,
    // not StateFlows — cheap to read here and pass down. The actual
    // StateFlow collection for each category's values happens inside its
    // own branch in SettingsCategoryContent so a flow update outside the
    // current category doesn't recompose the whole screen.
    val app = LocalContext.current.applicationContext as WristotleApplication
    val reminderSettings = app.reminderSettings
    val weatherSettings = app.weatherSettings
    val askAgentSettings = app.askAgentSettings
    val scope = rememberCoroutineScope()

    // A rescan can prune aliases whose target was uninstalled; the alias card's
    // VM is independent, so reload it whenever a scan finishes (lastScannedAtMs
    // changes) to keep the list in sync.
    val appIndexState by appIndexVm.state.collectAsState()
    LaunchedEffect(appIndexState.lastScannedAtMs) { appAliasesVm.refresh() }
    // State for the "you're about to shrink the window and lose N entries" confirm dialog.
    var pendingShrink by remember { mutableStateOf<PendingShrink?>(null) }
    var showClearLearnedConfirm by remember { mutableStateOf(false) }
    var showClearAudioConfirm by remember { mutableStateOf(false) }

    // Drill-down: null = landing, non-null = that category's sub-screen.
    // System back resets to null when on a sub-screen.
    var category by remember { mutableStateOf<SettingsCategory?>(null) }
    BackHandler(enabled = category != null) { category = null }

    // First-launch-after-install/update: drill straight into Help with
    // the just-installed version pre-filled. The capturedPrefill state
    // is the dance to avoid a race — onWhatsNewConsumed() nulls the
    // upstream MainScreen state before HelpCard ever composes, so we
    // copy the value into local state first, then signal upstream to
    // clear. HelpCard reads from capturedPrefill, not from the param
    // chain that's about to go null.
    var capturedPrefill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(whatsNewVersion) {
        if (whatsNewVersion != null) {
            capturedPrefill = whatsNewVersion
            category = SettingsCategory.Help
            onWhatsNewConsumed()
        }
    }

    // Welcome-wizard handoff: when the wizard's "Open settings" landed
    // us here, drill directly into the action's target category instead
    // of dumping the user on the landing list.
    LaunchedEffect(initialCategory) {
        if (initialCategory != null) {
            category = initialCategory
            onInitialCategoryConsumed()
        }
    }

    // One scroll state shared by landing + every sub-category. Reset
    // to top on every category transition so drilling into a category
    // doesn't inherit a scroll offset from wherever the user was on
    // the landing list (or on the prior category — the SetupCard's
    // "Open" buttons swap categories without recomposing the outer
    // Column, so a scrolled Setup card would otherwise drop the user
    // mid-page in the next category).
    val scrollState = rememberScrollState()
    LaunchedEffect(category) { scrollState.scrollTo(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val current = category
        if (current == null) {
            SettingsLanding(
                onCategorySelected = { category = it },
                attentionByCategory = attentionByCategory,
            )
        } else {
            SettingsCategoryHeader(current, onBack = { category = null })
            SettingsCategoryContent(
                category = current,
                modelsVm = modelsVm,
                nluModelsVm = nluModelsVm,
                nluSettingsVm = nluSettingsVm,
                conversationVm = conversationVm,
                appIndexVm = appIndexVm,
                appAliasesVm = appAliasesVm,
                contactAliasesVm = contactAliasesVm,
                notesVm = notesVm,
                diagnosticsVm = diagnosticsVm,
                watchSettingsVm = watchSettingsVm,
                backupVm = backupVm,
                mcpServersVm = mcpServersVm,
                reminderSettings = reminderSettings,
                weatherSettings = weatherSettings,
                askAgentSettings = askAgentSettings,
                onOpenCategory = { category = it },
                onOpenTopLevelTab = onOpenTopLevelTab,
                onShowClearLearnedConfirm = { showClearLearnedConfirm = true },
                onShowClearAudioConfirm = { showClearAudioConfirm = true },
                helpHighlightVersion = capturedPrefill,
                onHelpHighlightConsumed = { capturedPrefill = null },
                onShrinkRequest = { newDays ->
                    val currentDays = conversationVm.retentionDays.value
                    if (newDays >= currentDays) {
                        conversationVm.setRetentionDays(newDays)
                    } else {
                        scope.launch {
                            val count = conversationVm.countOlderThan(newDays)
                            pendingShrink = PendingShrink(newDays, count)
                        }
                    }
                },
            )
        }
    }

    pendingShrink?.let { shrink ->
        ShrinkConfirmDialog(
            newDays = shrink.newDays,
            entriesToDelete = shrink.entriesToDelete,
            onConfirm = {
                conversationVm.setRetentionDays(shrink.newDays)
                pendingShrink = null
            },
            onDismiss = { pendingShrink = null },
        )
    }

    if (showClearLearnedConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_learning_clear_title),
            message = stringResource(R.string.settings_learning_clear_message),
            confirmLabel = stringResource(R.string.settings_learning_clear_apply),
            onConfirm = {
                nluSettingsVm.clearLearned()
                showClearLearnedConfirm = false
            },
            onDismiss = { showClearLearnedConfirm = false },
        )
    }

    if (showClearAudioConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_audio_clear_title),
            message = stringResource(R.string.settings_audio_clear_message),
            confirmLabel = stringResource(R.string.settings_audio_clear_apply),
            onConfirm = {
                conversationVm.deleteAllAudio()
                showClearAudioConfirm = false
            },
            onDismiss = { showClearAudioConfirm = false },
        )
    }
}

/**
 * Drill-down landing — single Card holding one tappable [ListItem] per
 * [SettingsCategory]. Order matches the enum declaration so adding a new
 * category is one-line wiring.
 */
@Composable
private fun SettingsLanding(
    onCategorySelected: (SettingsCategory) -> Unit,
    attentionByCategory: Map<SettingsCategory, Boolean> = emptyMap(),
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            val categories = SettingsCategory.entries
            categories.forEachIndexed { index, cat ->
                val needsAttention = attentionByCategory[cat] == true
                ListItem(
                    leadingContent = {
                        Text(
                            cat.emoji,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(cat.labelRes))
                            if (needsAttention) {
                                Spacer(Modifier.width(8.dp))
                                // Small red dot mirrors the bottom-nav badge — the
                                // attention indicator that brought the user here is
                                // pointed straight at the relevant category.
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(8.dp),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { onCategorySelected(cat) },
                )
                if (index < categories.lastIndex) HorizontalDivider()
            }
        }
    }
}

/**
 * Sub-screen header — back arrow + category label. Doubles as the title
 * since the global TopAppBar shows the app name across all tabs.
 */
@Composable
private fun SettingsCategoryHeader(category: SettingsCategory, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
            )
        }
        Text(
            stringResource(category.labelRes),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Renders the cards for the selected category. Each branch corresponds to
 * one [SettingsCategory] value — adding a category means adding a branch
 * here and an enum entry, nothing else changes.
 */
@Suppress("LongParameterList")
@Composable
private fun SettingsCategoryContent(
    category: SettingsCategory,
    modelsVm: WhisperModelsViewModel,
    nluModelsVm: NluModelsViewModel,
    nluSettingsVm: NluSettingsViewModel,
    conversationVm: ConversationViewModel,
    appIndexVm: AppIndexViewModel,
    appAliasesVm: AppAliasesViewModel,
    contactAliasesVm: ContactAliasesViewModel,
    notesVm: NotesViewModel,
    diagnosticsVm: DiagnosticsViewModel,
    watchSettingsVm: WatchSettingsViewModel,
    backupVm: BackupViewModel,
    mcpServersVm: McpServersViewModel,
    reminderSettings: ReminderSettings,
    weatherSettings: com.lazydevs.wristotle.settings.WeatherSettings,
    askAgentSettings: com.lazydevs.wristotle.agent.AskAgentSettings,
    /** Lets the 🌟 Setup card's "Open" buttons jump directly into the
     *  sub-screen for an action's [SettingsCategory] target instead of
     *  bouncing the user back to the landing page. */
    onOpenCategory: (SettingsCategory) -> Unit,
    /** Forwards "open this top-level tab" requests from the 🌟 Setup
     *  card up to MainScreen, which owns the bottom-nav controller. */
    onOpenTopLevelTab: (com.lazydevs.wristotle.ui.nav.Screen) -> Unit,
    onShowClearLearnedConfirm: () -> Unit,
    onShowClearAudioConfirm: () -> Unit,
    onShrinkRequest: (Int) -> Unit,
    /** When non-null, the Help card marks the matching FeatureEntry
     *  with a "✨ New" chip and auto-scrolls to it on first
     *  composition — used by the first-launch-after-install/update
     *  flow to point the user at what they just got without hiding
     *  the rest of the page. */
    helpHighlightVersion: String? = null,
    onHelpHighlightConsumed: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as WristotleApplication
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Each branch collects the flows it actually uses — when the user
        // is on, say, the Backup category, the Conversation / Reminder /
        // Learning flows aren't subscribed, so their updates don't
        // recompose this surface.
        when (category) {
            SettingsCategory.Setup ->
                SetupCard(
                    provider = app.setupHealthProvider,
                    setupSettings = app.setupSettings,
                    onOpenCategory = onOpenCategory,
                    onOpenTopLevelTab = onOpenTopLevelTab,
                )

            SettingsCategory.Watch ->
                WatchSettingsCard(vm = watchSettingsVm)

            SettingsCategory.Conversation -> {
                val retentionDays by conversationVm.retentionDays.collectAsState()
                val audioCaptureEnabled by conversationVm.audioCaptureEnabled.collectAsState()
                val companion by conversationVm.pebbleCompanion.collectAsState()
                HistoryRetentionCard(
                    selectedDays = retentionDays,
                    options = conversationVm.retentionOptions,
                    onSelect = onShrinkRequest,
                    onClear = conversationVm::clearAll,
                )
                // Audio capture only works when Wristotle's Whisper recognizer
                // is in the dictation path (microPebble). Under Core Devices
                // the audio never reaches us, so the toggle would be a no-op.
                if (companion.whisperAppliesToWatchDictation) {
                    AudioCaptureCard(
                        enabled = audioCaptureEnabled,
                        onToggle = conversationVm::setAudioCaptureEnabled,
                        onClearAudio = onShowClearAudioConfirm,
                    )
                }
                ConversationsSyncCard(
                    settings = app.conversationsSyncSettings,
                    coordinator = app.conversationsSyncCoordinator,
                )
            }

            SettingsCategory.Notes -> {
                NotesSettingsCard(vm = notesVm)
                NotesSyncCard(
                    settings = app.notesSyncSettings,
                    coordinator = app.notesSyncCoordinator,
                )
            }

            SettingsCategory.Reminders -> {
                val reminderDefaultMinutes by reminderSettings.defaultOffsetMin.collectAsState()
                val reminderIntervalMin by reminderSettings.defaultIntervalMin.collectAsState()
                val reminderMaxAttempts by reminderSettings.defaultMaxAttempts.collectAsState()
                AlarmsCard(
                    repository = app.alarmRepository,
                    dispatcher = app.alarmDispatcher,
                    settings = app.alarmSettings,
                )
                ReminderSettingsCard(
                    selectedMinutes = reminderDefaultMinutes,
                    options = ReminderSettings.ALLOWED_OFFSET_MIN,
                    onSelect = reminderSettings::setDefaultOffsetMin,
                    selectedIntervalMin = reminderIntervalMin,
                    intervalOptions = ReminderSettings.ALLOWED_INTERVAL_MIN,
                    onSelectInterval = reminderSettings::setDefaultIntervalMin,
                    selectedMaxAttempts = reminderMaxAttempts,
                    maxAttemptsOptions = ReminderSettings.ALLOWED_MAX_ATTEMPTS,
                    onSelectMaxAttempts = reminderSettings::setDefaultMaxAttempts,
                )
            }

            SettingsCategory.Notifications -> {
                val notifLogEnabled by app.notificationLogSettings.enabled.collectAsState()
                val notifLogCount by remember {
                    app.notificationLogDb.notificationPostDao()
                        .observeCountSince(com.lazydevs.wristotle.briefing.TodayRange.now().startMs)
                }.collectAsState(initial = 0)
                MorningBriefCard(
                    logEnabled = notifLogEnabled,
                    logCount = notifLogCount,
                    onToggleLog = app.notificationLogSettings::setEnabled,
                    onClearLog = app.notificationLogStore::deleteAll,
                )
            }

            SettingsCategory.Weather ->
                WeatherSettingsCard(settings = weatherSettings)

            SettingsCategory.Models -> {
                SttProviderCard(settings = app.sttProviderSettings)
                WhisperModelsCard(vm = modelsVm)
            }

            SettingsCategory.Learning -> {
                NluModelsCard(vm = nluModelsVm)
                AppIndexCard(vm = appIndexVm)
                AppAliasesCard(vm = appAliasesVm)
                ContactAliasesCard(vm = contactAliasesVm)
                val learnedExamples by nluSettingsVm.learnedExamples.collectAsState()
                val learningEnabled by nluSettingsVm.learningEnabled.collectAsState()
                IntentLearningCard(
                    learningEnabled = learningEnabled,
                    learnedExamples = learnedExamples,
                    onToggle = nluSettingsVm::setLearningEnabled,
                    onRefresh = nluSettingsVm::refresh,
                    onDeletePhrase = nluSettingsVm::deleteLearned,
                    onClearLearned = onShowClearLearnedConfirm,
                )
            }

            SettingsCategory.Backup ->
                BackupCard(vm = backupVm)

            SettingsCategory.Mcp ->
                McpServersCard(vm = mcpServersVm)

            SettingsCategory.AskAgent ->
                AskAgentSettingsCard(settings = askAgentSettings)

            SettingsCategory.Diagnostics ->
                DiagnosticsCard(vm = diagnosticsVm)

            SettingsCategory.Help -> {
                // Pass the captured highlight version to HelpCard
                // once, then null it. HelpCard captures its initial
                // scroll target via remember {}, so the second
                // composition (with highlight=null) doesn't re-trigger
                // a scroll — and user-driven re-entries to Help
                // (Landing → Help) don't replay the "new feature"
                // highlight either.
                val highlight = helpHighlightVersion
                LaunchedEffect(Unit) { onHelpHighlightConsumed() }
                HelpCard(highlightVersion = highlight)
            }

            SettingsCategory.Support ->
                SupportCard()
        }
    }
}

@Composable
private fun AudioCaptureCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClearAudio: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_audio_header),
                description = stringResource(R.string.settings_audio_desc),
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_audio_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            // Separate from the toggle — sometimes the user wants to wipe
            // existing recordings without flipping capture off.
            Button(
                onClick = onClearAudio,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_audio_clear_button))
            }
        }
    }
}

@Composable
private fun IntentLearningCard(
    learningEnabled: Boolean,
    learnedExamples: List<LearnedExampleRow>,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDeletePhrase: (id: Long) -> Unit,
    onClearLearned: () -> Unit,
) {
    var showLearnedDialog by remember { mutableStateOf(false) }

    // Re-read on (re)entry so dispatches that happened on the Conversation
    // tab show up here without a process restart. The bank isn't observable
    // so the VM snapshot would otherwise stay stale.
    LaunchedEffect(Unit) { onRefresh() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_learning_header),
                description = stringResource(R.string.settings_learning_desc),
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                // weight on the label so it gets all available width up to
                // the Switch; otherwise SpaceBetween lets the Text overflow
                // and the Switch renders on top of it.
                Text(
                    stringResource(R.string.settings_learning_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Switch(checked = learningEnabled, onCheckedChange = onToggle)
            }

            if (learnedExamples.isNotEmpty()) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { showLearnedDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.settings_learning_show, learnedExamples.size))
                }
            }

            Button(
                onClick = onClearLearned,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_learning_clear_button))
            }
        }
    }

    if (showLearnedDialog) {
        LearnedPhrasesDialog(
            rows = learnedExamples,
            onDelete = onDeletePhrase,
            onDismiss = { showLearnedDialog = false },
        )
    }
}

/**
 * Read-out of every learned phrase grouped by intent + per-row delete.
 * Header note reminds the user the data is on-device — the visibility
 * of `rawText` (which includes anything spoken, like contact names in
 * `"call John Smith"`) can otherwise be surprising.
 */
@Composable
private fun LearnedPhrasesDialog(
    rows: List<LearnedExampleRow>,
    onDelete: (id: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_learning_dialog_done))
            }
        },
        title = { Text(stringResource(R.string.settings_learning_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.settings_learning_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Group by intent so the user can scan one verb at a time.
                // Ordering is already (intent ASC, addedAt DESC) from the VM.
                val byIntent = rows.groupBy { it.intent }
                byIntent.forEach { (intent, intentRows) ->
                    Text(
                        prettyIntentLabel(intent),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    intentRows.forEach { row ->
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "“${row.rawText}”",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (row.usageCount > 1) {
                                    Text(
                                        stringResource(
                                            R.string.settings_learning_usage,
                                            row.usageCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            androidx.compose.material3.IconButton(
                                onClick = { onDelete(row.id) },
                            ) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(
                                        R.string.settings_learning_delete,
                                    ),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Map a stored `Intent.name` string to a user-friendly label for the
 * learned-phrases dialog. Round-trips through the [Intent] enum so a
 * rename in the enum becomes a compile error at the `when` here, not
 * a silent fall-through. Unknown / pre-rename strings show the raw
 * value rather than crashing.
 */
private fun prettyIntentLabel(intentName: String): String {
    val intent = runCatching { com.lazydevs.wristotle.speech.nlu.Intent.valueOf(intentName) }
        .getOrNull()
        ?: return intentName
    return when (intent) {
        com.lazydevs.wristotle.speech.nlu.Intent.Call -> "Call"
        com.lazydevs.wristotle.speech.nlu.Intent.SendMessage -> "Send message"
        com.lazydevs.wristotle.speech.nlu.Intent.Reminder -> "Reminder"
        com.lazydevs.wristotle.speech.nlu.Intent.Cancel -> "Cancel reminder"
        com.lazydevs.wristotle.speech.nlu.Intent.FindPhone -> "Find phone"
        // Default for the rest — the learning surface only really
        // matters for the handful above, so we show enum name rather
        // than maintaining a full table.
        else -> intent.name
    }
}

private data class PendingShrink(val newDays: Int, val entriesToDelete: Int)

@Composable
private fun ShrinkConfirmDialog(
    newDays: Int,
    entriesToDelete: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_history_shrink_title)) },
        text = {
            val message = if (entriesToDelete == 0) {
                // No data loss today; the dialog confirms the intent to
                // tighten future pruning.
                stringResource(R.string.settings_history_shrink_message_none, newDays)
            } else {
                pluralStringResource(
                    R.plurals.settings_history_shrink_message,
                    entriesToDelete,
                    entriesToDelete,
                    newDays,
                )
            }
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_history_shrink_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRetentionCard(
    selectedDays: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_history_header),
                description = stringResource(R.string.settings_history_retention_desc),
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = pluralStringResource(R.plurals.settings_history_retention_days, selectedDays, selectedDays),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_history_retention_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { days ->
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.settings_history_retention_days, days, days)) },
                            onClick = {
                                onSelect(days)
                                expanded = false
                            },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.settings_history_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { showConfirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.conversation_clear_all))
            }
        }
    }

    if (showConfirm) {
        ClearConfirmDialog(
            onConfirm = {
                onClear()
                showConfirm = false
            },
            onDismiss = { showConfirm = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSettingsCard(
    selectedMinutes: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    selectedIntervalMin: Int,
    intervalOptions: List<Int>,
    onSelectInterval: (Int) -> Unit,
    selectedMaxAttempts: Int,
    maxAttemptsOptions: List<Int>,
    onSelectMaxAttempts: (Int) -> Unit,
) {
    var offsetExpanded by remember { mutableStateOf(false) }
    var intervalExpanded by remember { mutableStateOf(false) }
    var attemptsExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_reminders_header),
                description = stringResource(R.string.settings_reminders_desc),
            )

            ExposedDropdownMenuBox(
                expanded = offsetExpanded,
                onExpandedChange = { offsetExpanded = it },
            ) {
                OutlinedTextField(
                    value = pluralStringResource(R.plurals.settings_reminders_default_minutes, selectedMinutes, selectedMinutes),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_reminders_default_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = offsetExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = offsetExpanded,
                    onDismissRequest = { offsetExpanded = false },
                ) {
                    options.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.settings_reminders_default_minutes, minutes, minutes)) },
                            onClick = {
                                onSelect(minutes)
                                offsetExpanded = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Persistent reminders sub-section. Two knobs — nag interval +
            // max attempts — wrapped in the same card so users see one
            // "Reminders" surface; CardTitleWithInfo on the parent already
            // tells them what reminders mean.
            Text(
                text = stringResource(R.string.settings_reminders_persistent_subheader),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_reminders_persistent_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExposedDropdownMenuBox(
                expanded = intervalExpanded,
                onExpandedChange = { intervalExpanded = it },
            ) {
                OutlinedTextField(
                    value = pluralStringResource(R.plurals.settings_reminders_default_minutes, selectedIntervalMin, selectedIntervalMin),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_reminders_interval_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = intervalExpanded,
                    onDismissRequest = { intervalExpanded = false },
                ) {
                    intervalOptions.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.settings_reminders_default_minutes, minutes, minutes)) },
                            onClick = {
                                onSelectInterval(minutes)
                                intervalExpanded = false
                            },
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = attemptsExpanded,
                onExpandedChange = { attemptsExpanded = it },
            ) {
                OutlinedTextField(
                    value = pluralStringResource(R.plurals.settings_reminders_max_attempts_value, selectedMaxAttempts, selectedMaxAttempts),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_reminders_max_attempts_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = attemptsExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = attemptsExpanded,
                    onDismissRequest = { attemptsExpanded = false },
                ) {
                    maxAttemptsOptions.forEach { attempts ->
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.settings_reminders_max_attempts_value, attempts, attempts)) },
                            onClick = {
                                onSelectMaxAttempts(attempts)
                                attemptsExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MorningBriefCard(
    logEnabled: Boolean,
    logCount: Int,
    onToggleLog: (Boolean) -> Unit,
    onClearLog: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_morning_brief_header),
                description = stringResource(R.string.settings_morning_brief_desc),
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_morning_brief_log_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Switch(checked = logEnabled, onCheckedChange = onToggleLog)
            }
            Text(
                stringResource(R.string.settings_morning_brief_log_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (logEnabled) {
                Text(
                    stringResource(R.string.settings_morning_brief_log_count, logCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onClearLog,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_morning_brief_log_clear))
                }
            }
        }
    }
}

@Composable
private fun ClearConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.conversation_clear_confirm_title),
        message = stringResource(R.string.conversation_clear_confirm_message),
        confirmLabel = stringResource(R.string.conversation_clear_all),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}