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
import com.lazydevs.wristotle.ui.MainScreen
import com.lazydevs.wristotle.ui.MainViewModel
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
    ) { vm.refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WristotleTheme {
                MainScreen(
                    vm = vm,
                    modelsVm = modelsVm,
                    onRequestPermissions = ::requestPermissions,
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
    }

    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECORD_AUDIO)
            // POST_NOTIFICATIONS is only a runtime permission on Android 13+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionRequest.launch(permissions.toTypedArray())
    }

    /**
     * Fires the system battery-optimization-exemption dialog if the app isn't
     * already whitelisted. Invoked from the runtime-perms result callback so
     * one tap of "Grant Permissions" walks the user through both prompts.
     */
    private fun maybeRequestBatteryOptimizationsExemption() {
        if (vm.isIgnoringBatteryOptimizations()) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        batteryOptimizationRequest.launch(intent)
    }
}
