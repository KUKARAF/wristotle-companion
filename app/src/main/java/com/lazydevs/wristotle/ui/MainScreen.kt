package com.lazydevs.wristotle.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Root screen showing watch-bridge permission state and voice input activation.
 *
 * @param vm                   Provides current state via StateFlows.
 * @param onRequestPermissions Triggered when the user taps the "Grant Permissions" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    modelsVm: WhisperModelsViewModel,
    onRequestPermissions: () -> Unit,
) {
    val perms by vm.permissions.collectAsState()
    val isDefaultVoiceProvider by vm.isDefaultVoiceProvider.collectAsState()
    val watchPermsGranted = perms.contacts && perms.callPhone && perms.sendSms
    val allPermsGranted = watchPermsGranted &&
        perms.recordAudio &&
        perms.ignoringBatteryOptimizations

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.main_screen_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Watch-listener status card.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.status_card_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (watchPermsGranted) stringResource(R.string.status_active)
                        else stringResource(R.string.status_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (watchPermsGranted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(stringResource(R.string.permissions_header), style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.primary)

            PermissionRow(
                stringResource(R.string.perm_contacts_label),
                stringResource(R.string.perm_contacts_desc),
                perms.contacts
            )
            PermissionRow(
                stringResource(R.string.perm_calls_label),
                stringResource(R.string.perm_calls_desc),
                perms.callPhone
            )
            PermissionRow(
                stringResource(R.string.perm_sms_label),
                stringResource(R.string.perm_sms_desc),
                perms.sendSms
            )
            PermissionRow(
                stringResource(R.string.perm_record_audio_label),
                stringResource(R.string.perm_record_audio_desc),
                perms.recordAudio
            )
            PermissionRow(
                stringResource(R.string.perm_battery_label),
                stringResource(R.string.perm_battery_desc),
                perms.ignoringBatteryOptimizations
            )

            if (!allPermsGranted) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.grant_permissions_button))
                }
            }

            VoiceInputCard(
                isDefaultProvider = isDefaultVoiceProvider,
                adbCommand = vm.adbActivationCommand,
            )

            WhisperModelsCard(vm = modelsVm)

            Text(
                stringResource(R.string.usage_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Voice-input activation card: shows whether this app is the system's default
 * voice recognition provider and offers a one-tap "copy ADB command" button.
 */
/** The two ways a user can flip Wristotle on as the system voice-input provider. */
private enum class ActivationMethod(val labelRes: Int) {
    Adb(R.string.voice_input_method_adb),
    Settings(R.string.voice_input_method_settings),
}

@Composable
private fun VoiceInputCard(
    isDefaultProvider: Boolean,
    adbCommand: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.voice_input_header), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.voice_input_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (isDefaultProvider) stringResource(R.string.voice_input_default_active)
                else stringResource(R.string.voice_input_default_inactive),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDefaultProvider) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )
            if (!isDefaultProvider) {
                ActivationMethodPicker(adbCommand = adbCommand)
            }
        }
    }
}

/**
 * Activation-method dropdown + the action / hint for whatever method is selected.
 * Single visible action keeps the card tight; switching methods is one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivationMethodPicker(adbCommand: String) {
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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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

/**
 * Tries to open the system's voice-input picker. The intent is documented but
 * only wired up on a subset of Android distributions — on the others we surface
 * a toast pointing the user to the ADB path.
 */
private fun openVoiceInputSettings(context: Context) {
    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.voice_input_settings_unavailable_toast, Toast.LENGTH_LONG).show()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ADB activation command", text))
    Toast.makeText(context, R.string.adb_command_copied_toast, Toast.LENGTH_LONG).show()
}

/**
 * A single row showing a permission's label, description, and grant icon.
 */
@Composable
private fun PermissionRow(label: String, description: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (granted) stringResource(R.string.content_desc_granted)
                                 else stringResource(R.string.content_desc_denied),
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error
        )
    }
}
