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
import androidx.compose.material3.MenuAnchorType
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
 * Voice Input tab — the two voice-only runtime permissions (Record Audio +
 * Battery Optimization exemption) plus the activation method picker that
 * lets the user flip Wristotle on as Android's system voice provider.
 *
 * Watch-bridge perms live on the Watch tab; this screen only deals with
 * what the speech recognition feature itself needs.
 */
@Composable
fun VoiceScreen(
    vm: MainViewModel,
    onRequestVoicePermissions: () -> Unit,
) {
    val perms by vm.permissions.collectAsState()
    val isDefaultProvider by vm.isDefaultVoiceProvider.collectAsState()
    val voicePermsGranted = perms.recordAudio && perms.ignoringBatteryOptimizations

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.voice_input_header),
                    style = MaterialTheme.typography.titleMedium,
                )
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

                Spacer(Modifier.height(4.dp))

                Text(
                    stringResource(R.string.permissions_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PermissionRow(
                    stringResource(R.string.perm_record_audio_label),
                    stringResource(R.string.perm_record_audio_desc),
                    perms.recordAudio,
                )
                PermissionRow(
                    stringResource(R.string.perm_battery_label),
                    stringResource(R.string.perm_battery_desc),
                    perms.ignoringBatteryOptimizations,
                )
                if (!voicePermsGranted) {
                    Button(
                        onClick = onRequestVoicePermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.grant_permissions_button))
                    }
                }

                if (!isDefaultProvider) {
                    Spacer(Modifier.height(4.dp))
                    VoiceActivationMethodPicker(adbCommand = vm.adbActivationCommand)
                }
            }
        }
    }
}

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
