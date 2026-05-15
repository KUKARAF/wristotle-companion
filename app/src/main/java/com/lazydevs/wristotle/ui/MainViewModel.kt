package com.lazydevs.wristotle.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazydevs.wristotle.speech.service.WhisperRecognitionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of the runtime permissions the companion app uses. */
data class PermissionState(
    val contacts: Boolean = false,
    val callPhone: Boolean = false,
    val sendSms: Boolean = false,
    val recordAudio: Boolean = false,
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
                    contacts    = app.hasPermission(Manifest.permission.READ_CONTACTS),
                    callPhone   = app.hasPermission(Manifest.permission.CALL_PHONE),
                    sendSms     = app.hasPermission(Manifest.permission.SEND_SMS),
                    recordAudio = app.hasPermission(Manifest.permission.RECORD_AUDIO),
                )
            }
            _isDefaultVoiceProvider.update { isThisAppTheDefaultVoiceProvider() }
        }
    }

    private fun isThisAppTheDefaultVoiceProvider(): Boolean {
        val current = Settings.Secure
            .getString(app.contentResolver, VOICE_RECOGNITION_SERVICE)
            ?.let { ComponentName.unflattenFromString(it) }
        val expected = ComponentName(app, WhisperRecognitionService::class.java)
        return current == expected
    }

    private fun Application.hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        // The Settings.Secure constant is hidden; the underlying setting name is
        // stable and documented across the Android source.
        const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    }
}
