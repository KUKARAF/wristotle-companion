package com.lazydevs.wristotle.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.speech.service.WhisperRecognitionService
import com.lazydevs.wristotle.util.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of the runtime permissions + system-setting state the companion app uses. */
data class PermissionState(
    val contacts: Boolean = false,
    /** True only when BOTH read and write calendar grants are held — read
     *  powers the query feature, write powers event creation. */
    val calendar: Boolean = false,
    val callPhone: Boolean = false,
    val sendSms: Boolean = false,
    val recordAudio: Boolean = false,
    /**
     * True when the app is on the system's battery-optimization-ignored list.
     * Keeps the foreground watch bridge alive under Doze and lowers the
     * service's kill priority under memory pressure.
     */
    val ignoringBatteryOptimizations: Boolean = false,
    /**
     * True when the user has granted Notification Access (a.k.a.
     * `BIND_NOTIFICATION_LISTENER_SERVICE`) — required for the
     * media-control intents to enumerate active media sessions via
     * `MediaSessionManager.getActiveSessions(...)`.
     */
    val mediaControl: Boolean = false,
    /**
     * True when the user has granted SYSTEM_ALERT_WINDOW ("Display over
     * other apps"). Wristotle never actually draws an overlay; holding
     * the grant alone gives us `BAL_ALLOW_SAW_PERMISSION` in Android's
     * BackgroundActivityStartController, which exempts our `startActivity`
     * calls from the Android 14+ BAL restriction. Optional — without it,
     * watch-triggered `open <app>` keeps hitting the BAL wall on cold
     * start, same as today.
     */
    val canDrawOverlays: Boolean = false,
    /**
     * True when `ACCESS_COARSE_LOCATION` is granted. Used by the bare
     * "what's the weather" path to read the phone's last-known location.
     * Optional — without it the user can still say "weather in &lt;city&gt;".
     */
    val coarseLocation: Boolean = false,
)

/**
 * Holds permission state and voice-input activation state for [MainScreen].
 *
 * Survives configuration changes. Uses [AndroidViewModel] rather than plain
 * [androidx.lifecycle.ViewModel] because the permission and Settings.Secure
 * checks need the application [android.content.Context].
 */
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions

    private val _isDefaultVoiceProvider = MutableStateFlow(false)
    val isDefaultVoiceProvider: StateFlow<Boolean> = _isDefaultVoiceProvider

    /**
     * Surfaces which BLE companion is in front of Wristotle, so the
     * Permissions screen's Voice Input card can downrank the "set as
     * default voice provider" UI when rePebble is active — rePebble
     * bypasses Android's SpeechRecognizer entirely, so flipping
     * Wristotle on as the system voice provider doesn't affect watch
     * dictation under it. See [PebbleCompanionDetector] for the
     * detection model.
     */
    private val companionDetector =
        (app as com.lazydevs.wristotle.WristotleApplication).pebbleCompanionDetector
    val pebbleCompanion: StateFlow<com.lazydevs.wristotle.transport.PebbleCompanionDetector.State> =
        companionDetector.state

    /** Voice-Input-card-specific dismissal of the cloud-dictation scope
     *  note. Independent of the same notice's state on the Conversation
     *  tab and the Whisper Models card. */
    val voiceInputScopeNoteDismissed: StateFlow<Boolean> =
        companionDetector.voiceInputScopeNoteDismissed
    fun dismissVoiceInputScopeNote() = companionDetector.dismissVoiceInputScopeNote()

    /**
     * The exact ADB invocation that flips this app on as the system voice input
     * provider. Built from the live ComponentName so it stays correct if the
     * service is ever renamed or repackaged.
     */
    val adbActivationCommand: String =
        "adb shell settings put secure $VOICE_RECOGNITION_SERVICE " +
            ComponentName(app, WhisperRecognitionService::class.java).flattenToString()

    /**
     * Re-reads current grants from the system and the voice-input default provider.
     * Called on activity create and resume so the UI stays in sync after the user
     * returns from system settings.
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            _permissions.update {
                PermissionState(
                    contacts                     = app.hasPermission(Manifest.permission.READ_CONTACTS),
                    calendar                     = app.hasPermission(Manifest.permission.READ_CALENDAR) &&
                                                       app.hasPermission(Manifest.permission.WRITE_CALENDAR),
                    callPhone                    = app.hasPermission(Manifest.permission.CALL_PHONE),
                    sendSms                      = app.hasPermission(Manifest.permission.SEND_SMS),
                    recordAudio                  = app.hasPermission(Manifest.permission.RECORD_AUDIO),
                    ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
                    mediaControl                 = hasNotificationAccess(),
                    canDrawOverlays              = Settings.canDrawOverlays(app),
                    coarseLocation               = app.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
            _isDefaultVoiceProvider.update { isThisAppTheDefaultVoiceProvider() }
        }
    }

    /**
     * Returns true when the app is on the system's battery-optimization-ignored
     * list. Read via [PowerManager] — there's no runtime permission to check;
     * the setting is flipped via the [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     * system dialog (see `MainActivity.batteryOptimizationRequest`).
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = app.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    /**
     * True iff the user has granted Notification Access — the system
     * gate that unlocks `MediaSessionManager.getActiveSessions(...)`.
     * Flipped via the system Settings page `ACTION_NOTIFICATION_LISTENER_SETTINGS`;
     * MainActivity launches that intent from the Permissions card.
     */
    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    private fun isThisAppTheDefaultVoiceProvider(): Boolean {
        val current = Settings.Secure
            .getString(app.contentResolver, VOICE_RECOGNITION_SERVICE)
            ?.let { ComponentName.unflattenFromString(it) }
        val expected = ComponentName(app, WhisperRecognitionService::class.java)
        return current == expected
    }

    private companion object {
        // The Settings.Secure constant is hidden; the underlying setting name is
        // stable and documented across the Android source.
        const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    }
}
