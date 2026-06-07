// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.ui.nav.Screen
import com.lazydevs.wristotle.ui.SettingsCategory

/**
 * Top-level app shell: TopAppBar + bottom NavigationBar + NavHost.
 *
 * Each tab is a focused feature screen (Watch / Voice / Models). The
 * NavigationBar surfaces a badge on a tab whenever something on that
 * screen needs the user's attention — missing perms, no active model,
 * etc. — so users know which tabs they need to visit on first launch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    modelsVm: WhisperModelsViewModel,
    nluModelsVm: NluModelsViewModel,
    nluSettingsVm: NluSettingsViewModel,
    conversationVm: ConversationViewModel,
    appIndexVm: AppIndexViewModel,
    appAliasesVm: AppAliasesViewModel,
    contactAliasesVm: ContactAliasesViewModel,
    notesVm: NotesViewModel,
    tasksVm: TasksViewModel,
    diagnosticsVm: DiagnosticsViewModel,
    watchSettingsVm: WatchSettingsViewModel,
    backupVm: BackupViewModel,
    mcpServersVm: McpServersViewModel,
    onRequestWatchPermissions: () -> Unit,
    onRequestVoicePermissions: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    /** When non-null, navigate to the Settings tab on first compose AND
     *  drill straight into this category's sub-screen. Used by the
     *  first-launch wizard's "Open settings" path to drop the user
     *  exactly where they need to be without re-tapping. Consumed
     *  once via [onInitialSettingsCategoryConsumed] so it doesn't
     *  re-fire on configuration changes. */
    initialSettingsCategory: SettingsCategory? = null,
    onInitialSettingsCategoryConsumed: () -> Unit = {},
    /** Same one-shot pattern as [initialSettingsCategory], but jumps to
     *  a top-level bottom-nav tab outside Settings (today: only
     *  [Screen.Permissions]). Used by the wizard's "Open settings" path
     *  when the action targets the Permissions tab. */
    initialTopLevelTab: Screen? = null,
    onInitialTopLevelTabConsumed: () -> Unit = {},
    /** Fires whenever the top-level NavHost destination changes (user
     *  taps a different tab, system-back leaves a sub-screen, etc.).
     *  Used by MainActivity to un-hide a paused first-launch wizard so
     *  the user comes back to it after completing an action in Settings. */
    onNavDestinationChanged: () -> Unit = {},
) {
    val navController = rememberNavController()
    val perms by vm.permissions.collectAsState()

    // Notify the host whenever the destination changes — covers tab
    // taps, system back, and any programmatic navigation. The
    // currentBackStackEntryAsState observation below already exists for
    // the BottomNav selection; piggybacking on its updates avoids a
    // separate listener.
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.destination?.route) {
        onNavDestinationChanged()
    }

    // Captured pending-state holders. **Do NOT** key the remember on
    // the upstream param — the LaunchedEffect below calls the consume
    // callback as soon as it navigates, which nulls the host's state.
    // Re-keying the remember would then reset the local copy too,
    // **before** the NavHost mounts SettingsScreen for the first time,
    // so SettingsScreen would land with initialCategory=null and dump
    // the user on the landing list instead of the requested category.
    //
    // Same dance as the `capturedPrefill` pattern inside SettingsScreen
    // for whatsNewVersion. The LaunchedEffect below copies the upstream
    // value into the local state whenever it transitions to non-null,
    // and SettingsScreen's onInitialCategoryConsumed callback is what
    // clears the local state once SettingsScreen has captured it.
    var pendingSettingsCategory by remember {
        mutableStateOf<SettingsCategory?>(initialSettingsCategory)
    }
    LaunchedEffect(initialSettingsCategory) {
        initialSettingsCategory?.let { pendingSettingsCategory = it }
    }
    LaunchedEffect(pendingSettingsCategory) {
        if (pendingSettingsCategory != null) {
            navController.navigate(Screen.Settings.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onInitialSettingsCategoryConsumed()
        }
    }

    // Setup-card / welcome-wizard "open a top-level tab" channel.
    // Routes that aren't a Settings sub-category — today only
    // [Screen.Permissions]. Same captured pattern as
    // `pendingSettingsCategory` above so the local state survives the
    // upstream caller nulling its value once the consume callback
    // fires. SettingsScreen / the wizard never read this — the
    // LaunchedEffect navigates and clears the local state on the same
    // pass, so there's no consumer-side capture race.
    var pendingTabSwitch by remember {
        mutableStateOf<Screen?>(initialTopLevelTab)
    }
    LaunchedEffect(initialTopLevelTab) {
        initialTopLevelTab?.let { pendingTabSwitch = it }
    }
    LaunchedEffect(pendingTabSwitch) {
        pendingTabSwitch?.let { screen ->
            navController.navigate(screen.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onInitialTopLevelTabConsumed()
            pendingTabSwitch = null
        }
    }

    // Auto-open Settings → ❓ Help on the first launch after a fresh
    // install or a version upgrade. WhatsNewState.consumeOnce() returns
    // the just-installed version exactly once per version change; null
    // on every subsequent call (and across cold starts on the same
    // version). Captured into a mutableStateOf so SettingsScreen can
    // observe it as a one-shot trigger via LaunchedEffect.
    val app = LocalContext.current.applicationContext as WristotleApplication
    var whatsNewVersion by remember { mutableStateOf(app.whatsNewState.consumeOnce()) }
    LaunchedEffect(whatsNewVersion) {
        if (whatsNewVersion != null) {
            navController.navigate(Screen.Settings.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val isDefaultVoiceProvider by vm.isDefaultVoiceProvider.collectAsState()
    // Only the boolean — see ModelsViewModel.attentionNeeded for why we don't
    // collect the full models list here (download progress would recompose
    // the whole shell every ~100 ms during a 244 MB Whisper download).
    val modelsAttention by modelsVm.attentionNeeded.collectAsState()
    val companion by vm.pebbleCompanion.collectAsState()

    // Permissions tab badges if any of the watch perms (Contacts/Phone/SMS),
    // voice perms (Record Audio + battery exemption), or the default-voice-
    // provider setting still need user attention. Under a cloud-dictation
    // companion (Core Devices, rePebble) Wristotle's Whisper isn't in the
    // watch dictation path — Record Audio + default-voice-provider don't
    // matter for it to work, so drop those from the gate. Battery exemption
    // is kept either way: the foreground watch bridge needs it regardless
    // of which companion is in front. Settings tab badges if no Whisper
    // model is downloaded/active yet — the only blocking thing in Settings.
    val permissionsAttention = !(perms.contacts && perms.callPhone && perms.sendSms) ||
        !perms.ignoringBatteryOptimizations ||
        (companion.whisperAppliesToWatchDictation && (!perms.recordAudio || !isDefaultVoiceProvider))

    // Per-category attention map for the Settings tab. The drill-down landing
    // shows a dot on each row that has attention; the bottom-nav Settings tab
    // badges if any value is true. Currently only the Models category drives
    // attention (no Whisper model downloaded/active), and only when Whisper
    // is actually in the watch dictation path — under Core Devices/rePebble
    // the user has no reason to install a model, so the dot would otherwise
    // be a permanent false-positive.
    val settingsAttentionByCategory: Map<SettingsCategory, Boolean> = mapOf(
        SettingsCategory.Models to (companion.whisperAppliesToWatchDictation && modelsAttention),
    )
    val settingsAttention = settingsAttentionByCategory.values.any { it }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.main_screen_title)) }) },
        bottomBar = {
            NavigationBar {
                val currentRoute = navController.currentBackStackEntryAsState()
                    .value?.destination?.route
                Screen.entries.forEach { screen ->
                    val attention = when (screen) {
                        Screen.Conversation -> false
                        Screen.Notes        -> false
                        Screen.Tasks        -> false
                        Screen.Permissions  -> permissionsAttention
                        Screen.Settings     -> settingsAttention
                    }
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                // Standard bottom-nav navigation options: pop back to the
                                // start destination so the back stack doesn't pile up, and
                                // restore the destination's saved state if we've been there
                                // before.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (attention) {
                                BadgedBox(badge = {
                                    Badge(
                                        modifier = Modifier,
                                        containerColor = MaterialTheme.colorScheme.error,
                                    )
                                }) {
                                    Icon(
                                        screen.icon,
                                        contentDescription = stringResource(R.string.nav_attention_badge_desc),
                                    )
                                }
                            } else {
                                Icon(screen.icon, contentDescription = null)
                            }
                        },
                        // Labels stay on one line even when a particular phone's
                        // tab width is tight ("Permissions" was wrapping to a 2nd row
                        // with an orphan "s" on narrow devices); ellipsize as a
                        // belt-and-braces fallback.
                        label = {
                            Text(
                                stringResource(screen.labelRes),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // Per-tab tint: selected icon + indicator pill take the screen's
                        // brand color; unselected uses a desaturated alpha so the tab
                        // still hints at its identity without competing with the
                        // selected one.
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = screen.tint,
                            selectedTextColor = screen.tint,
                            indicatorColor = screen.tint.copy(alpha = 0.15f),
                            unselectedIconColor = screen.tint.copy(alpha = 0.65f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Start.route,
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            composable(Screen.Conversation.route) {
                Box(padding) {
                    ConversationScreen(vm = conversationVm)
                }
            }
            composable(Screen.Notes.route) {
                Box(padding) {
                    NotesScreen(vm = notesVm)
                }
            }
            composable(Screen.Tasks.route) {
                Box(padding) {
                    TasksScreen(vm = tasksVm)
                }
            }
            composable(Screen.Permissions.route) {
                Box(padding) {
                    PermissionsScreen(
                        vm = vm,
                        onRequestWatchPermissions = onRequestWatchPermissions,
                        onRequestVoicePermissions = onRequestVoicePermissions,
                        onRequestLocationPermission = onRequestLocationPermission,
                    )
                }
            }
            composable(Screen.Settings.route) {
                Box(padding) {
                    SettingsScreen(
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
                        attentionByCategory = settingsAttentionByCategory,
                        whatsNewVersion = whatsNewVersion,
                        onWhatsNewConsumed = { whatsNewVersion = null },
                        initialCategory = pendingSettingsCategory,
                        onInitialCategoryConsumed = { pendingSettingsCategory = null },
                        onOpenTopLevelTab = { pendingTabSwitch = it },
                    )
                }
            }
        }
    }
}

/** Tiny wrapper that applies the Scaffold's content padding to a screen. */
@Composable
private fun Box(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
        content()
    }
}

/** A single permission row with label / description / grant-state icon. */
@Composable
internal fun PermissionRow(label: String, description: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (granted) stringResource(R.string.content_desc_granted)
                                 else stringResource(R.string.content_desc_denied),
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error,
        )
    }
}