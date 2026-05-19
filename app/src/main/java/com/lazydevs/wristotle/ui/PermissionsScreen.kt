package com.lazydevs.wristotle.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Single tab for everything permission-related: the watch-bridge runtime
 * permissions (Contacts / Phone / SMS) and the voice-input perms (Record
 * Audio, battery optimization exemption) plus the system voice-provider
 * activation picker. Two stacked cards in one scroll envelope so users
 * see all the permission state in one place.
 */
@Composable
fun PermissionsScreen(
    vm: MainViewModel,
    onRequestWatchPermissions: () -> Unit,
    onRequestVoicePermissions: () -> Unit,
) {
    val perms by vm.permissions.collectAsState()
    val isDefaultProvider by vm.isDefaultVoiceProvider.collectAsState()
    val companion by vm.pebbleCompanion.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WatchBridgeCard(
            contactsGranted = perms.contacts,
            callPhoneGranted = perms.callPhone,
            sendSmsGranted = perms.sendSms,
            onRequest = onRequestWatchPermissions,
        )
        VoiceInputCard(
            recordAudioGranted = perms.recordAudio,
            batteryOptimizationGranted = perms.ignoringBatteryOptimizations,
            isDefaultVoiceProvider = isDefaultProvider,
            adbCommand = vm.adbActivationCommand,
            onRequest = onRequestVoicePermissions,
            whisperBypassedForWatch = !companion.whisperAppliesToWatchDictation,
        )
        MediaControlCard(
            granted = perms.mediaControl,
            onOpenSettings = { openNotificationListenerSettings(context) },
        )
        Text(
            stringResource(R.string.usage_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openNotificationListenerSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.media_control_no_settings_toast),
            Toast.LENGTH_LONG,
        ).show()
    }
}

// ── Watch Bridge card ───────────────────────────────────────────────────────

@Composable
private fun WatchBridgeCard(
    contactsGranted: Boolean,
    callPhoneGranted: Boolean,
    sendSmsGranted: Boolean,
    onRequest: () -> Unit,
) {
    val allGranted = contactsGranted && callPhoneGranted && sendSmsGranted

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.status_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (allGranted) stringResource(R.string.status_active)
                else stringResource(R.string.status_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = if (allGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(4.dp))

            PermissionRow(
                stringResource(R.string.perm_contacts_label),
                stringResource(R.string.perm_contacts_desc),
                contactsGranted,
            )
            PermissionRow(
                stringResource(R.string.perm_calls_label),
                stringResource(R.string.perm_calls_desc),
                callPhoneGranted,
            )
            PermissionRow(
                stringResource(R.string.perm_sms_label),
                stringResource(R.string.perm_sms_desc),
                sendSmsGranted,
            )
            if (!allGranted) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.grant_permissions_button))
                }
            }
        }
    }
}

// ── Voice Input card ───────────────────────────────────────────────────────

@Composable
private fun VoiceInputCard(
    recordAudioGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    isDefaultVoiceProvider: Boolean,
    adbCommand: String,
    onRequest: () -> Unit,
    whisperBypassedForWatch: Boolean,
) {
    val voicePermsGranted = recordAudioGranted && batteryOptimizationGranted

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.voice_input_header),
                description = stringResource(R.string.voice_input_desc),
            )
            if (whisperBypassedForWatch) {
                // Doesn't affect watch dictation under rePebble (rePebble
                // routes through its own cloud, not Android's
                // SpeechRecognizer). Surface this here so the user
                // understands what activating Wristotle as the system
                // voice provider actually buys them — keyboards, search
                // bars, other apps — not the watch.
                Text(
                    stringResource(R.string.voice_input_repebble_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (isDefaultVoiceProvider) stringResource(R.string.voice_input_default_active)
                else stringResource(R.string.voice_input_default_inactive),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDefaultVoiceProvider) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(4.dp))

            PermissionRow(
                stringResource(R.string.perm_record_audio_label),
                stringResource(R.string.perm_record_audio_desc),
                recordAudioGranted,
            )
            PermissionRow(
                stringResource(R.string.perm_battery_label),
                stringResource(R.string.perm_battery_desc),
                batteryOptimizationGranted,
            )
            if (!voicePermsGranted) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.grant_permissions_button))
                }
            }

            if (!isDefaultVoiceProvider) {
                Spacer(Modifier.height(4.dp))
                VoiceActivationMethodPicker(adbCommand = adbCommand)
            }
        }
    }
}

// ── Voice activation method picker (preserved from VoiceScreen) ────────────

/** The two ways a user can flip Wristotle on as the system voice-input provider. */
private enum class ActivationMethod(val labelRes: Int) {
    Adb(R.string.voice_input_method_adb),
    Settings(R.string.voice_input_method_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceActivationMethodPicker(adbCommand: String) {
    val context = LocalContext.current
    var method by rememberSaveable { mutableStateOf(ActivationMethod.Adb) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(method.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.voice_input_method_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ActivationMethod.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        method = option
                        expanded = false
                    },
                )
            }
        }
    }

    when (method) {
        ActivationMethod.Adb -> {
            Text(
                stringResource(R.string.voice_input_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { copyToClipboard(context, adbCommand) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.copy_adb_command_button))
            }
        }
        ActivationMethod.Settings -> {
            Text(
                stringResource(R.string.voice_input_settings_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { openVoiceInputSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_input_try_settings_button))
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ADB activation command", text))
    Toast.makeText(context, R.string.adb_command_copied_toast, Toast.LENGTH_LONG).show()
}

private fun openVoiceInputSettings(context: Context) {
    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.voice_input_settings_unavailable_toast, Toast.LENGTH_LONG).show()
    }
}

// ── Media Control card ─────────────────────────────────────────────────────

/**
 * Compact card that surfaces the Notification Access state and offers a
 * "Open Settings" button to grant it. Notification Access is the gate
 * Android puts in front of `MediaSessionManager.getActiveSessions()`,
 * which the `MediaXxxHandler`s need to control whatever app is currently
 * playing audio.
 */
@Composable
private fun MediaControlCard(
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.media_control_header),
                description = stringResource(R.string.media_control_desc),
            )
            Text(
                if (granted) stringResource(R.string.media_control_active)
                else stringResource(R.string.media_control_inactive),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )
            // Button hides once granted — the system page is a long list
            // of every notification-listener app, awkward to land on
            // when there's nothing to change.
            if (!granted) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.media_control_open_settings))
                }
            }
        }
    }
}
