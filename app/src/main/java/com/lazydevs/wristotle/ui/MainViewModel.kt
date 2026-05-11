package com.lazydevs.wristotle.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of the three runtime permissions the companion app requires. */
data class PermissionState(
    val contacts: Boolean = false,
    val callPhone: Boolean = false,
    val sendSms: Boolean = false,
)

/**
 * Holds permission state for [MainScreen] and survives configuration changes.
 *
 * Uses [AndroidViewModel] rather than plain [androidx.lifecycle.ViewModel] because
 * checking permissions requires the application [android.content.Context].
 */
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions

    /**
     * Re-reads the current permission grants from the system and updates [permissions].
     * Called on activity create and resume so the UI stays in sync after the user
     * returns from the system permission dialog.
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            _permissions.update {
                PermissionState(
                    contacts  = app.hasPermission(Manifest.permission.READ_CONTACTS),
                    callPhone = app.hasPermission(Manifest.permission.CALL_PHONE),
                    sendSms   = app.hasPermission(Manifest.permission.SEND_SMS),
                )
            }
        }
    }

    private fun Application.hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
