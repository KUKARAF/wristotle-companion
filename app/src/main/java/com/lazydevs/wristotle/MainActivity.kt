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
    private val diagnosticsVm: DiagnosticsViewModel by viewModels()
    private val watchSettingsVm: WatchSettingsViewModel by viewModels()

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
                MainScreen(
                    vm = vm,
                    modelsVm = modelsVm,
                    nluModelsVm = nluModelsVm,
                    nluSettingsVm = nluSettingsVm,
                    conversationVm = conversationVm,
                    appIndexVm = appIndexVm,
                    diagnosticsVm = diagnosticsVm,
                    watchSettingsVm = watchSettingsVm,
                    onRequestWatchPermissions = ::requestWatchPermissions,
                    onRequestVoicePermissions = ::requestVoicePermissions,
                )
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
    }

    /** Runtime perms the watch-bridge handlers need (Watch tab Grant button). */
    private fun requestWatchPermissions() {
        val permissions = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
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
