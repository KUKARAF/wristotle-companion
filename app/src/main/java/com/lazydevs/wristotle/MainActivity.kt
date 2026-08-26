// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lazydevs.wristotle.service.WatchMessageService
import com.lazydevs.wristotle.ui.AppIndexViewModel
import com.lazydevs.wristotle.ui.ConversationViewModel
import com.lazydevs.wristotle.ui.DiagnosticsViewModel
import com.lazydevs.wristotle.ui.MainScreen
import com.lazydevs.wristotle.ui.MainViewModel
import com.lazydevs.wristotle.ui.NluModelsViewModel
import com.lazydevs.wristotle.ui.NluSettingsViewModel
import com.lazydevs.wristotle.ui.WatchSettingsViewModel
import com.lazydevs.wristotle.ui.WhisperModelsViewModel
import com.lazydevs.wristotle.ui.theme.WristotleTheme

/**
 * Single-activity entry point for the companion app.
 *
 * Responsibilities:
 * - Starts [WatchMessageService] as a foreground service on launch.
 * - Requests the permissions the service needs (contacts, call, SMS, notifications).
 * - Provides [MainScreen] with a callback to trigger the system permission dialog.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val modelsVm: WhisperModelsViewModel by viewModels()
    private val nluModelsVm: NluModelsViewModel by viewModels()
    private val nluSettingsVm: NluSettingsViewModel by viewModels()
    private val conversationVm: ConversationViewModel by viewModels()
    private val appIndexVm: AppIndexViewModel by viewModels()
    private val appAliasesVm: com.lazydevs.wristotle.ui.AppAliasesViewModel by viewModels()
    private val contactAliasesVm: com.lazydevs.wristotle.ui.ContactAliasesViewModel by viewModels()
    private val notesVm: com.lazydevs.wristotle.ui.NotesViewModel by viewModels()
    private val tasksVm: com.lazydevs.wristotle.ui.TasksViewModel by viewModels()
    private val diagnosticsVm: DiagnosticsViewModel by viewModels()
    private val watchSettingsVm: WatchSettingsViewModel by viewModels()
    private val backupVm: com.lazydevs.wristotle.ui.BackupViewModel by viewModels()
    private val mcpServersVm: com.lazydevs.wristotle.ui.McpServersViewModel by viewModels()
    private val statsVm: com.lazydevs.wristotle.ui.StatsViewModel by viewModels()

    // Registered once; result arrives asynchronously and triggers a permission refresh.
    // After the runtime perms dialog resolves, chain into the battery-optimization
    // system dialog if the app isn't already whitelisted — the foreground watch
    // bridge gets killed under Doze on aggressive OEM ROMs without that whitelist.
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refreshPermissions()
        maybeRequestBatteryOptimizationsExemption()
    }

    private val batteryOptimizationRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.refreshPermissions()
        // Chain to notification access *after* the battery dialog so
        // the user steps through one system page at a time during
        // first-run rather than getting two stacked at once.
        maybeRequestNotificationAccess()
    }

    private val notificationAccessRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WristotleTheme {
                val app = applicationContext as WristotleApplication

                // Welcome wizard: shown on first launch (or after the
                // user re-arms it via "Show welcome again"). Stays hidden
                // when there are no essential pending actions — power
                // users on Core Devices with everything granted don't
                // see it at all.
                val wizardDismissed by app.setupSettings.welcomeWizardDismissed
                    .collectAsState()
                // Reactive setup-health refresh — mirrors how the
                // "attention dot" on bottom-nav badges itself off
                // [modelsVm.attentionNeeded] directly. Each of these
                // StateFlows surfaces a piece of state that feeds
                // SetupHealthProvider.derive(); observing them here
                // (and re-deriving on any tick) means we don't have
                // to remember to call refresh() at every state-mutation
                // site — the imported-Whisper path was the canary for
                // why per-site notifications are fragile.
                val currentPerms by vm.permissions.collectAsState()
                val whisperAttn by modelsVm.attentionNeeded.collectAsState()
                val nluAttn by nluModelsVm.attentionNeeded.collectAsState()
                val pebbleCompanion by vm.pebbleCompanion.collectAsState()
                val appIndexState by appIndexVm.state.collectAsState()
                val appAliases by appAliasesVm.aliases.collectAsState()
                val contactAliases by contactAliasesVm.aliases.collectAsState()
                LaunchedEffect(
                    currentPerms,
                    whisperAttn,
                    nluAttn,
                    pebbleCompanion,
                    appIndexState.lastScannedAtMs,
                    appAliases.size,
                    contactAliases.size,
                ) {
                    app.setupHealthProvider.refresh()
                }
                val pendingActions by app.setupHealthProvider.actions.collectAsState()
                val essentials = remember(pendingActions) {
                    pendingActions.filter {
                        it.priority == com.lazydevs.wristotle.setup.Priority.Essential
                    }
                }
                var pendingSettingsCategory by remember {
                    mutableStateOf<com.lazydevs.wristotle.ui.SettingsCategory?>(null)
                }
                // Same one-shot pattern as [pendingSettingsCategory] but
                // for routes that aren't a Settings sub-category — today
                // only [Screen.Permissions], used by the wizard's
                // GrantPermissions action.
                var pendingTopLevelTab by remember {
                    mutableStateOf<com.lazydevs.wristotle.ui.nav.Screen?>(null)
                }
                // Wizard's "Open settings" sets this flag so the dialog
                // stops blocking the underlying Settings sub-screen. The
                // wizard is NOT dismissed (the persistent flag stays
                // false) — auto-un-hides when (a) the essentials list
                // shrinks (user just completed an action) or (b) the
                // user navigates to a different top-level destination.
                var wizardHidden by remember { mutableStateOf(false) }
                // The wizard-triggered nav itself fires onNavDestinationChanged,
                // which would immediately un-hide the wizard over the Settings
                // sub-screen. Consume one nav-change tick on the way in so
                // only the user's NEXT navigation un-hides.
                var consumeNextNavChange by remember { mutableStateOf(false) }
                // Track the size at the moment we hid the wizard. If
                // it drops below this baseline (because the user
                // completed the action), un-hide so the wizard
                // visibly auto-advances to the next pending step
                // without forcing the user to navigate elsewhere first.
                var essentialsSizeAtHide by remember { mutableIntStateOf(essentials.size) }
                LaunchedEffect(essentials.size, wizardHidden) {
                    if (wizardHidden && essentials.size < essentialsSizeAtHide) {
                        wizardHidden = false
                    }
                }
                // Wizard step state is **hoisted** here so it survives
                // the temporary hide/show cycle when the user taps
                // "Open settings", does the action, and the wizard
                // reappears. The composable inside WelcomeWizard would
                // lose the index because its `remember` gets disposed
                // when the wizard is removed from the tree.
                var wizardStepIndex by remember { mutableIntStateOf(0) }

                MainScreen(
                    vm = vm,
                    modelsVm = modelsVm,
                    nluModelsVm = nluModelsVm,
                    nluSettingsVm = nluSettingsVm,
                    conversationVm = conversationVm,
                    appIndexVm = appIndexVm,
                    appAliasesVm = appAliasesVm,
                    contactAliasesVm = contactAliasesVm,
                    notesVm = notesVm,
                    tasksVm = tasksVm,
                    diagnosticsVm = diagnosticsVm,
                    watchSettingsVm = watchSettingsVm,
                    backupVm = backupVm,
                    mcpServersVm = mcpServersVm,
                    statsVm = statsVm,
                    onRequestWatchPermissions = ::requestWatchPermissions,
                    onRequestVoicePermissions = ::requestVoicePermissions,
                    onRequestLocationPermission = ::requestLocationPermission,
                    initialSettingsCategory = pendingSettingsCategory,
                    onInitialSettingsCategoryConsumed = { pendingSettingsCategory = null },
                    initialTopLevelTab = pendingTopLevelTab,
                    onInitialTopLevelTabConsumed = { pendingTopLevelTab = null },
                    // Any time the top-level destination changes (user
                    // taps a tab, system-back leaves a sub-screen, etc.)
                    // un-hide the wizard so the user comes back to it.
                    // When the action they just completed has dropped
                    // from the essentials list, SetupHealthProvider has
                    // already re-derived and the wizard's stepIndex
                    // effectively points at the next pending action.
                    //
                    // Skip the FIRST nav change after `wizardHidden`
                    // flipped to true — that's the wizard-triggered
                    // navigation into Settings, not a user action.
                    onNavDestinationChanged = {
                        if (consumeNextNavChange) {
                            consumeNextNavChange = false
                        } else if (wizardHidden) {
                            wizardHidden = false
                        }
                    },
                )

                // Recommended-model download state for the wizard's inline
                // one-tap download (replaces the "open Settings → Models" punt).
                val whisperModelStates by modelsVm.models.collectAsState()
                val nluModelStates by nluModelsVm.models.collectAsState()
                val whisperRec = com.lazydevs.wristotle.speech.whisper.ModelCatalog.recommendedDefault()
                val nluRec = com.lazydevs.wristotle.speech.nlu.model.NluModelCatalog.recommendedDefault()
                val whisperDlState = whisperModelStates.firstOrNull { it.info.id == whisperRec.id }
                val nluDlState = nluModelStates.firstOrNull { it.info.id == nluRec.id }

                if (!wizardDismissed && !wizardHidden) {
                    com.lazydevs.wristotle.ui.WelcomeWizard(
                        essentialActions = essentials,
                        whisperDownload = com.lazydevs.wristotle.ui.WizardModelDownload(
                            onStart = { modelsVm.download(whisperRec.id) },
                            progress = whisperDlState?.progress,
                            approxSizeBytes = whisperRec.approxSizeBytes,
                            error = whisperDlState?.errorMessage,
                        ),
                        nluDownload = com.lazydevs.wristotle.ui.WizardModelDownload(
                            onStart = { nluModelsVm.download(nluRec.id) },
                            progress = nluDlState?.progress,
                            approxSizeBytes = nluRec.approxSizeBytes,
                            error = nluDlState?.errorMessage,
                        ),
                        stepIndex = wizardStepIndex,
                        onStepIndexChange = { wizardStepIndex = it },
                        onSkipWizard = { app.setupSettings.dismissWelcomeWizard() },
                        onOpenCategory = { cat ->
                            // Hide the wizard so its full-screen Dialog
                            // stops blocking the Settings sub-screen,
                            // but DO NOT set the persistent dismissed
                            // flag. Auto-un-hides when essentials shrinks
                            // (user completed the action) OR when the
                            // user navigates away.
                            wizardHidden = true
                            consumeNextNavChange = true
                            essentialsSizeAtHide = essentials.size
                            pendingSettingsCategory = cat
                        },
                        onOpenTopLevelTab = { screen ->
                            // Same hide-but-don't-dismiss flow as
                            // onOpenCategory, but the action's target
                            // is a top-level tab (e.g. Permissions)
                            // outside the Settings drill-down.
                            wizardHidden = true
                            consumeNextNavChange = true
                            essentialsSizeAtHide = essentials.size
                            pendingTopLevelTab = screen
                        },
                        onFinish = { app.setupSettings.dismissWelcomeWizard() },
                    )
                }
            }
        }

        val intent = Intent(this, WatchMessageService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        vm.refreshPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case the user granted/revoked permissions in system settings
        // while the app was in the background.
        vm.refreshPermissions()
        modelsVm.refresh()
        appIndexVm.refresh()
        appAliasesVm.refresh()
        contactAliasesVm.refresh()
    }

    /** Runtime perms the watch-bridge handlers need (Watch tab Grant button). */
    private fun requestWatchPermissions() {
        val permissions = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            // POST_NOTIFICATIONS is only a runtime permission on Android 13+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionRequest.launch(permissions.toTypedArray())
    }

    /**
     * Runtime perms the speech recognition feature needs (Voice tab Grant
     * button). The post-grant callback chains into the battery-optimization
     * exemption dialog if it hasn't been granted yet.
     */
    private fun requestVoicePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionRequest.launch(permissions.toTypedArray())
    }

    /**
     * Runtime perm the bare "what's the weather" voice command needs (Weather
     * card on the Permissions tab). Coarse location only — city-level
     * accuracy is plenty for weather and it's less invasive than fine. Doesn't
     * chain into the battery / notification flow because nothing else
     * depends on it.
     */
    private fun requestLocationPermission() {
        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /**
     * Fires the system battery-optimization-exemption dialog if the app isn't
     * already whitelisted. Invoked from the runtime-perms result callback so
     * one tap of "Grant Permissions" walks the user through all the prompts.
     */
    private fun maybeRequestBatteryOptimizationsExemption() {
        if (vm.isIgnoringBatteryOptimizations()) {
            // Already exempt; skip to the next step in the chain.
            maybeRequestNotificationAccess()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        batteryOptimizationRequest.launch(intent)
    }

    /**
     * Opens the system Notification Listener Access settings if we don't
     * have it yet. Notification Access is what unlocks the cross-app
     * media-control feature (play/pause/next any app from the watch).
     * Last step in the first-run permission chain.
     */
    private fun maybeRequestNotificationAccess() {
        if (vm.hasNotificationAccess()) return
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            notificationAccessRequest.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            // Device lacks the system page (rare). Silently skip — the
            // Media Control card still surfaces the manual entry point.
        }
    }
}