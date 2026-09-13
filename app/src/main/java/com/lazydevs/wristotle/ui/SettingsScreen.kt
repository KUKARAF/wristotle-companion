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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
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
import com.lazydevs.wristotle.speech.nlu.settings.ReminderSettings
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
/** Modern-layout section headers grouping the categories on the Settings
 *  landing. Classic layout ignores these. */
enum class SettingsSection(@param:StringRes val labelRes: Int) {
    Watch(R.string.settings_group_watch),
    VoiceAi(R.string.settings_group_voice_ai),
    Features(R.string.settings_group_features),
    DataSystem(R.string.settings_group_data_system),
    About(R.string.settings_group_about),
}

enum class SettingsCategory(
    @param:StringRes val labelRes: Int,
    val emoji: String,
    /** Marks the category as work-in-progress; the landing list shows an
     *  "Experimental" pill next to the label so users know to set
     *  expectations before opening it. */
    val experimental: Boolean = false,
    /** Advanced categories are hidden on the Settings landing by default
     *  (behind "Show advanced settings") so a new user sees a short, core
     *  list instead of ~20 entries. Core = the essentials + everyday
     *  features; advanced = optional integrations, experimental, diagnostics. */
    val advanced: Boolean = false,
    /** Modern layout: which section header this category groups under. */
    val section: SettingsSection = SettingsSection.Features,
    /** Modern layout: optional relabel of the drill-down title (e.g. Models →
     *  "Models & learning" when it absorbs the Learning cards). */
    @param:StringRes val modernLabelRes: Int? = null,
    /** Extra search terms so the Settings search finds this category by more
     *  than its visible label (e.g. "api key" → Ask Agent). */
    val keywords: List<String> = emptyList(),
) {
    Setup(R.string.settings_section_setup, "🌟", section = SettingsSection.Watch,
        keywords = listOf("setup", "getting started")),
    Watch(R.string.settings_section_watch, "⌚", section = SettingsSection.Watch,
        keywords = listOf("watch", "timer", "vibrate", "quick launch", "buttons", "cards", "shortcuts")),
    Conversation(R.string.settings_section_conversation, "💬", section = SettingsSection.VoiceAi,
        keywords = listOf("history", "retention", "audio")),
    Notes(R.string.settings_section_notes, "📝", section = SettingsSection.Features,
        keywords = listOf("notes", "sync", "export", "folder")),
    Tasks(R.string.settings_section_tasks, "📋", section = SettingsSection.Features,
        keywords = listOf("tasks", "todo", "export", "checklist", "sync")),
    Codes(R.string.settings_section_codes, "🎟️", section = SettingsSection.Watch,
        keywords = listOf("qr", "barcode", "loyalty", "codes")),
    Reminders(R.string.settings_section_reminders, "⏰", section = SettingsSection.Features,
        keywords = listOf("reminder", "alarm", "nag")),
    Calendar(R.string.settings_section_calendar, "📅", section = SettingsSection.Features,
        keywords = listOf("calendar", "event", "account")),
    Notifications(R.string.settings_section_notifications, "🔔", section = SettingsSection.Features,
        keywords = listOf("notifications", "morning brief")),
    Weather(R.string.settings_section_weather, "☁️", advanced = true, section = SettingsSection.Features,
        keywords = listOf("weather", "temperature", "forecast")),
    Sport(R.string.settings_section_sport, "🏆", advanced = true, section = SettingsSection.Features,
        keywords = listOf("sports", "scores", "teams", "leagues")),
    Models(R.string.settings_section_models, "🧠", section = SettingsSection.VoiceAi,
        modernLabelRes = R.string.settings_section_models_modern,
        keywords = listOf("whisper", "model", "speech", "download", "learning", "aliases", "nlu")),
    Learning(R.string.settings_section_learning, "🎓", section = SettingsSection.VoiceAi,
        keywords = listOf("learning", "aliases", "contacts", "apps", "nlu", "phrases")),
    Backup(R.string.settings_section_backup, "💾", section = SettingsSection.DataSystem,
        keywords = listOf("backup", "restore", "export", "import", "zip")),
    Mcp(R.string.settings_section_mcp, "🔌", advanced = true, section = SettingsSection.VoiceAi,
        keywords = listOf("mcp", "tools", "servers")),
    AskAgent(R.string.settings_section_askagent, "✨", advanced = true, section = SettingsSection.VoiceAi,
        keywords = listOf("ask agent", "llm", "ai", "claude", "api key", "openai", "routing", "headers", "mcp", "memory")),
    HomeAssistant(R.string.settings_section_homeassistant, "🏠", advanced = true, section = SettingsSection.VoiceAi,
        keywords = listOf("home assistant", "ha", "smart home")),
    Speech(R.string.settings_section_speech, "🔊", experimental = true, advanced = true, section = SettingsSection.VoiceAi,
        keywords = listOf("speech", "tts", "speak", "voice", "on-watch")),
    Stats(R.string.settings_section_stats, "📊", advanced = true, section = SettingsSection.DataSystem,
        modernLabelRes = R.string.settings_section_system_modern,
        keywords = listOf("stats", "activity", "system", "diagnostics", "logs")),
    Diagnostics(R.string.settings_section_diagnostics, "🔧", advanced = true, section = SettingsSection.DataSystem,
        keywords = listOf("diagnostics", "logs", "bug report")),
    Help(R.string.settings_section_help, "❓", section = SettingsSection.About,
        modernLabelRes = R.string.settings_section_about_modern,
        keywords = listOf("help", "about", "version", "changelog", "support", "donate")),
    Support(R.string.settings_section_support, "❤️", section = SettingsSection.About,
        keywords = listOf("support", "donate", "funding")),
}

/** Modern layout only: each key category is folded INTO its parent's drill-down
 *  (its cards render under the parent; it doesn't get its own landing row).
 *  Classic layout ignores this and shows every category standalone. */
val SETTINGS_MERGES: Map<SettingsCategory, SettingsCategory> = mapOf(
    SettingsCategory.Mcp to SettingsCategory.AskAgent,
    SettingsCategory.Learning to SettingsCategory.Models,
    SettingsCategory.Diagnostics to SettingsCategory.Stats,
)

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
    statsVm: StatsViewModel,
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
    val homeAssistantSettings = app.homeAssistantSettings
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
    // Landing-only search text. Cleared whenever the user drills into a
    // category so it doesn't linger when they come back.
    var searchQuery by remember { mutableStateOf("") }
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
        // One content renderer reused for the open category and — in the
        // grouped (modern) layout — for any child categories folded into it,
        // so the merge shows the child's cards inline under the parent.
        val renderContent: @Composable (SettingsCategory) -> Unit = { cat ->
            SettingsCategoryContent(
                category = cat,
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
                statsVm = statsVm,
                reminderSettings = reminderSettings,
                weatherSettings = weatherSettings,
                askAgentSettings = askAgentSettings,
                homeAssistantSettings = homeAssistantSettings,
                onOpenCategory = { category = it },
                onOpenTopLevelTab = onOpenTopLevelTab,
                onShowClearLearnedConfirm = { showClearLearnedConfirm = true },
                onShowClearAudioConfirm = { showClearAudioConfirm = true },
                helpHighlightVersion = if (cat == SettingsCategory.Help) capturedPrefill else null,
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
        val useClassic by app.setupSettings.useClassicSettingsLayout.collectAsState()
        if (current == null) {
            val showAdvanced by app.setupSettings.settingsShowAdvanced.collectAsState()
            // Search box sits above whichever landing layout is active.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_back))
                        }
                    }
                },
            )
            val query = searchQuery.trim()
            when {
                query.isNotEmpty() -> SettingsSearchResults(
                    query = query,
                    onCategorySelected = { category = it },
                    attentionByCategory = attentionByCategory,
                )
                useClassic -> {
                    SettingsLanding(
                        onCategorySelected = { category = it },
                        attentionByCategory = attentionByCategory,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = { app.setupSettings.setSettingsShowAdvanced(it) },
                    )
                    LayoutToggleRow(
                        classic = true,
                        onToggle = { app.setupSettings.setUseClassicSettingsLayout(it) },
                    )
                }
                else -> {
                    SettingsLandingModern(
                        onCategorySelected = { category = it },
                        attentionByCategory = attentionByCategory,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = { app.setupSettings.setSettingsShowAdvanced(it) },
                    )
                    LayoutToggleRow(
                        classic = false,
                        onToggle = { app.setupSettings.setUseClassicSettingsLayout(it) },
                    )
                }
            }
        } else {
            val modern = !useClassic
            SettingsCategoryHeader(current, onBack = { category = null }, modern = modern)
            renderContent(current)
            if (modern) {
                // Grouped layout: fold each merged child's cards in under the
                // parent with a sub-header so they read as one screen.
                SETTINGS_MERGES.filter { it.value == current }.keys.forEach { child ->
                    SettingsSubHeader(stringResource(child.labelRes), child.emoji)
                    renderContent(child)
                }
            }
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
    showAdvanced: Boolean = false,
    onToggleAdvanced: (Boolean) -> Unit = {},
) {
    val core = SettingsCategory.entries.filter { !it.advanced }
    val advanced = SettingsCategory.entries.filter { it.advanced }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            core.forEachIndexed { index, cat ->
                CategoryRow(cat, attentionByCategory[cat] == true, onCategorySelected)
                if (index < core.lastIndex) HorizontalDivider()
            }
            HorizontalDivider()
            // Advanced toggle — keeps the landing short for a new user while a
            // power user reveals integrations / diagnostics with one tap. An
            // advanced category needing attention still surfaces via the Setup
            // card and its bottom-nav badge, so hiding it here is safe.
            ListItem(
                leadingContent = {
                    Text("⚙️", style = MaterialTheme.typography.titleLarge)
                },
                headlineContent = { Text(stringResource(R.string.settings_show_advanced)) },
                trailingContent = {
                    Switch(checked = showAdvanced, onCheckedChange = onToggleAdvanced)
                },
                modifier = Modifier.clickable { onToggleAdvanced(!showAdvanced) },
            )
            if (showAdvanced) {
                advanced.forEach { cat ->
                    HorizontalDivider()
                    CategoryRow(cat, attentionByCategory[cat] == true, onCategorySelected)
                }
            }
        }
    }
}

/**
 * Grouped ("modern") landing — the default. Categories are bucketed under
 * [SettingsSection] headers, and any category folded into a parent via
 * [SETTINGS_MERGES] is omitted here (its cards render under the parent's
 * drill-down instead). Advanced categories stay hidden until the toggle at
 * the bottom, same as the classic landing.
 */
@Composable
private fun SettingsLandingModern(
    onCategorySelected: (SettingsCategory) -> Unit,
    attentionByCategory: Map<SettingsCategory, Boolean> = emptyMap(),
    showAdvanced: Boolean = false,
    onToggleAdvanced: (Boolean) -> Unit = {},
) {
    val standalone = SettingsCategory.entries.filter { it !in SETTINGS_MERGES.keys }
    SettingsSection.entries.forEach { section ->
        val inSection = standalone.filter { it.section == section }
        val shown = inSection.filter { !it.advanced } +
            if (showAdvanced) inSection.filter { it.advanced } else emptyList()
        if (shown.isEmpty()) return@forEach
        Text(
            stringResource(section.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                shown.forEachIndexed { index, cat ->
                    CategoryRow(
                        cat,
                        attentionByCategory[cat] == true,
                        onCategorySelected,
                        labelRes = cat.modernLabelRes ?: cat.labelRes,
                    )
                    if (index < shown.lastIndex) HorizontalDivider()
                }
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Text("⚙️", style = MaterialTheme.typography.titleLarge) },
            headlineContent = { Text(stringResource(R.string.settings_show_advanced)) },
            trailingContent = {
                Switch(checked = showAdvanced, onCheckedChange = onToggleAdvanced)
            },
            modifier = Modifier.clickable { onToggleAdvanced(!showAdvanced) },
        )
    }
}

/**
 * Flat filtered list shown while the search box has text. Matches a
 * category by its visible label, its enum name, or any of its [keywords],
 * across every category (including merged children — searching "mcp" still
 * drills straight to the MCP cards).
 */
@Composable
private fun SettingsSearchResults(
    query: String,
    onCategorySelected: (SettingsCategory) -> Unit,
    attentionByCategory: Map<SettingsCategory, Boolean> = emptyMap(),
) {
    val q = query.lowercase()
    val matches = SettingsCategory.entries
        .map { it to stringResource(it.labelRes) }
        .filter { (cat, label) ->
            label.lowercase().contains(q) ||
                cat.name.lowercase().contains(q) ||
                cat.keywords.any { it.contains(q) }
        }
        .map { it.first }
    if (matches.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_no_results, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                matches.forEachIndexed { index, cat ->
                    CategoryRow(cat, attentionByCategory[cat] == true, onCategorySelected)
                    if (index < matches.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/** Footer toggle to flip between the grouped (default) and classic flat
 *  landing. [classic] is the current state; tapping flips it. */
@Composable
private fun LayoutToggleRow(classic: Boolean, onToggle: (Boolean) -> Unit) {
    TextButton(onClick = { onToggle(!classic) }) {
        Text(
            stringResource(
                if (classic) R.string.settings_use_grouped_layout
                else R.string.settings_use_classic_layout,
            ),
        )
    }
}

/** Sub-header shown above a merged child's cards in the grouped layout. */
@Composable
private fun SettingsSubHeader(label: String, emoji: String) {
    Text(
        "$emoji  $label",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** Thin theme label grouping several cards inside one category's drill-down
 *  (a level finer than [SettingsSubHeader]). Coarser than each card's own
 *  title so it adds a scannable layer instead of echoing it. */
@Composable
private fun SettingsGroupLabel(@StringRes labelRes: Int) {
    Text(
        stringResource(labelRes).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun CategoryRow(
    cat: SettingsCategory,
    needsAttention: Boolean,
    onCategorySelected: (SettingsCategory) -> Unit,
    @StringRes labelRes: Int = cat.labelRes,
) {
    ListItem(
        leadingContent = {
            Text(
                cat.emoji,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(labelRes))
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
                if (cat.experimental) {
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            "Experimental",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
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
}

/**
 * Sub-screen header — back arrow + category label. Doubles as the title
 * since the global TopAppBar shows the app name across all tabs.
 */
@Composable
private fun SettingsCategoryHeader(
    category: SettingsCategory,
    onBack: () -> Unit,
    modern: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
            )
        }
        Text(
            stringResource(if (modern) category.modernLabelRes ?: category.labelRes else category.labelRes),
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
    statsVm: StatsViewModel,
    reminderSettings: ReminderSettings,
    weatherSettings: com.lazydevs.wristotle.speech.nlu.settings.WeatherSettings,
    askAgentSettings: com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings,
    homeAssistantSettings: com.lazydevs.wristotle.speech.nlu.settings.HomeAssistantSettings,
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

            SettingsCategory.Watch -> {
                SettingsGroupLabel(R.string.settings_grp_interaction)
                WatchSettingsCard(vm = watchSettingsVm)
                SettingsGroupLabel(R.string.settings_grp_display)
                CardDisplaySettingsCard(settings = app.cardSettings)
                SettingsGroupLabel(R.string.settings_grp_timer)
                TimerSettingsCard(settings = app.timerSettings)
            }

            SettingsCategory.Conversation -> {
                val retentionDays by conversationVm.retentionDays.collectAsState()
                val audioCaptureEnabled by conversationVm.audioCaptureEnabled.collectAsState()
                val companion by conversationVm.pebbleCompanion.collectAsState()
                SettingsGroupLabel(R.string.settings_grp_stored_on_phone)
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
                SettingsGroupLabel(R.string.settings_grp_sync_export)
                ConversationsSyncCard(
                    settings = app.conversationsSyncSettings,
                    coordinator = app.conversationsSyncCoordinator,
                )
            }

            SettingsCategory.Notes -> {
                SettingsGroupLabel(R.string.settings_grp_storage)
                NotesSettingsCard(vm = notesVm)
                SettingsGroupLabel(R.string.settings_grp_sync_export)
                NotesSyncCard(
                    settings = app.notesSyncSettings,
                    coordinator = app.notesSyncCoordinator,
                )
            }

            SettingsCategory.Tasks -> {
                TasksSyncCard(
                    settings = app.tasksSyncSettings,
                    coordinator = app.tasksSyncCoordinator,
                )
            }

            SettingsCategory.Calendar -> {
                CalendarSettingsCard(settings = app.calendarSettings)
            }

            SettingsCategory.Codes -> {
                CodesScreen(vm = androidx.lifecycle.viewmodel.compose.viewModel())
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
                        .observeCountSince(com.lazydevs.wristotle.speech.nlu.briefing.TodayRange.now().startMs)
                }.collectAsState(initial = 0)
                SettingsGroupLabel(R.string.settings_grp_morning_brief)
                MorningBriefCard(
                    logEnabled = notifLogEnabled,
                    logCount = notifLogCount,
                    onToggleLog = app.notificationLogSettings::setEnabled,
                    onClearLog = app.notificationLogStore::deleteAll,
                )
                MorningBriefSettingsCard(settings = app.briefSettings)
            }

            SettingsCategory.Weather ->
                WeatherSettingsCard(settings = weatherSettings)

            SettingsCategory.Sport ->
                SportSettingsCard(settings = app.sportSettings, source = app.sportSource)

            SettingsCategory.Models -> {
                SettingsGroupLabel(R.string.settings_grp_speech_to_text)
                SttProviderCard(settings = app.sttProviderSettings)
                WhisperModelsCard(vm = modelsVm)
            }

            SettingsCategory.Learning -> {
                SettingsGroupLabel(R.string.settings_grp_language_model)
                NluModelsCard(vm = nluModelsVm)
                SettingsGroupLabel(R.string.settings_grp_aliases)
                AppIndexCard(vm = appIndexVm)
                AppAliasesCard(vm = appAliasesVm)
                ContactAliasesCard(vm = contactAliasesVm)
                SettingsGroupLabel(R.string.settings_grp_learned_phrases)
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

            SettingsCategory.HomeAssistant ->
                HomeAssistantSettingsCard(
                    settings = homeAssistantSettings,
                    askAgentSettings = askAgentSettings,
                )

            SettingsCategory.Speech ->
                TtsProviderCard(settings = app.ttsProviderSettings)

            SettingsCategory.Stats ->
                StatsCard(vm = statsVm)

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