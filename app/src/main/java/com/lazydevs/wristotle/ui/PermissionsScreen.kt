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
import androidx.compose.foundation.layout.Row
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
 * Single tab for everything permission-related. Three stacked cards in
 * one scroll envelope:
 *
 *   - **Watch Bridge** — Contacts / Phone / SMS + the battery-
 *     optimization exemption (the FGS keeping the bridge alive needs
 *     all four; one Grant button chains through them).
 *   - **Voice Input (Whisper)** — Record Audio + activation picker for
 *     making Wristotle the system voice provider. Mostly relevant for
 *     system-wide voice input outside the watch; hidden when the
 *     active BLE companion bypasses Android SpeechRecognizer anyway.
 *   - **Media Control** — the Notification Access toggle gating
 *     cross-app media playback control.
 */
@Composable
fun PermissionsScreen(
    vm: MainViewModel,
    onRequestWatchPermissions: () -> Unit,
    onRequestVoicePermissions: () -> Unit,
    onRequestLocationPermission: () -> Unit,
) {
    val perms by vm.permissions.collectAsState()
    val isDefaultProvider by vm.isDefaultVoiceProvider.collectAsState()
    val companion by vm.pebbleCompanion.collectAsState()
    val voiceInputScopeNoteDismissed by vm.voiceInputScopeNoteDismissed.collectAsState()
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
            calendarGranted = perms.calendar,
            callPhoneGranted = perms.callPhone,
            sendSmsGranted = perms.sendSms,
            batteryOptimizationGranted = perms.ignoringBatteryOptimizations,
            onRequest = onRequestWatchPermissions,
        )
        VoiceInputCard(
            recordAudioGranted = perms.recordAudio,
            isDefaultVoiceProvider = isDefaultProvider,
            adbCommand = vm.adbActivationCommand,
            onRequest = onRequestVoicePermissions,
            // The scope note only renders when also not-yet-dismissed.
            showScopeNote = !companion.whisperAppliesToWatchDictation
                && !voiceInputScopeNoteDismissed,
            // Voice-input rows (Record Audio, default-provider status,
            // ADB picker) hide entirely when the user is on a cloud-
            // dictation companion — independent of whether the scope
            // note has been dismissed. Dismissing the note just means
            // "I've read this," not "show me the irrelevant rows again."
            voiceInputAppliesToWatch = companion.whisperAppliesToWatchDictation,
            onDismissScopeNote = vm::dismissVoiceInputScopeNote,
        )
        MediaControlCard(
            granted = perms.mediaControl,
            onOpenSettings = { openNotificationListenerSettings(context) },
        )
        WeatherLocationCard(
            granted = perms.coarseLocation,
            onRequest = onRequestLocationPermission,
        )
        BackgroundLaunchCard(
            granted = perms.canDrawOverlays,
            onOpenSettings = { openManageOverlayPermission(context) },
        )
    }
}

private fun openManageOverlayPermission(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.background_launch_no_settings_toast),
            Toast.LENGTH_LONG,
        ).show()
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
    calendarGranted: Boolean,
    callPhoneGranted: Boolean,
    sendSmsGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    onRequest: () -> Unit,
) {
    // Battery exemption belongs here even though it isn't a runtime
    // permission per se — the watch-bridge foreground service can't
    // stay alive without it. The watch-perms grant flow already chains
    // through the battery-optimization-exemption system dialog, so a
    // single tap of Grant Permissions covers them all.
    val allGranted = contactsGranted && calendarGranted && callPhoneGranted &&
        sendSmsGranted && batteryOptimizationGranted

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
                stringResource(R.string.perm_calendar_label),
                stringResource(R.string.perm_calendar_desc),
                calendarGranted,
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
            PermissionRow(
                stringResource(R.string.perm_battery_label),
                stringResource(R.string.perm_battery_desc),
                batteryOptimizationGranted,
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
    isDefaultVoiceProvider: Boolean,
    adbCommand: String,
    onRequest: () -> Unit,
    showScopeNote: Boolean,
    voiceInputAppliesToWatch: Boolean,
    onDismissScopeNote: () -> Unit,
) {
    // Battery-optimization exemption moved out to the WatchBridgeCard
    // — it isn't really a voice-input concern (the FGS needs it
    // regardless of dictation path). What's left here is the genuine
    // voice-input permission gate: Record Audio + activation picker.

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.voice_input_header),
                description = stringResource(R.string.voice_input_desc),
            )
            if (showScopeNote) {
                // Doesn't affect watch dictation under rePebble or Core
                // Devices (both route through their own cloud, not
                // Android's SpeechRecognizer). Wrap the note in a
                // tertiaryContainer card so it stands out clearly from
                // the parent VoiceInputCard's surface — surfaceVariant
                // blended into surface in dark mode. Dismissable.
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.voice_input_repebble_scope_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Button(
                                onClick = onDismissScopeNote,
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                ),
                            ) {
                                Text(stringResource(R.string.conversation_repebble_notice_dismiss))
                            }
                        }
                    }
                }
            }
            // Voice-input-specific rows (status, Record Audio, ADB
            // picker) only render when voice input is actually relevant
            // on this device. Under a cloud-dictation companion all of
            // these are misleading. Battery row is kept always — the
            // exemption is needed by WatchMessageService regardless of
            // the voice path, so users still need a way to see/regrant
            // it from this tab.
            if (voiceInputAppliesToWatch) {
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
                if (!recordAudioGranted) {
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
/**
 * Optional "Display over other apps" / SYSTEM_ALERT_WINDOW grant —
 * a spike to see whether `BAL_ALLOW_SAW_PERMISSION` in Android's
 * BackgroundActivityStartController exempts our startActivity calls
 * from the Android 14+ BAL restriction (which currently makes
 * watch-triggered `open <app>` silently fail on cold start when
 * Wristotle's PebbleListenerService is in BOUND_FGS state).
 *
 * Wristotle never actually draws an overlay — holding the grant alone
 * is what matters. UX warning is honest: SAW is a sensitive permission
 * users may distrust. Card hides nothing else; the fallback (no grant)
 * leaves today's BAL-wall behaviour in place.
 */
@Composable
private fun BackgroundLaunchCard(
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.background_launch_header),
                description = stringResource(R.string.background_launch_desc),
            )
            Text(
                if (granted) stringResource(R.string.background_launch_active)
                else stringResource(R.string.background_launch_inactive),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.background_launch_open_settings))
                }
            }
        }
    }
}

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

@Composable
private fun WeatherLocationCard(
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.weather_location_header),
                description = stringResource(R.string.weather_location_desc),
            )
            Text(
                if (granted) stringResource(R.string.weather_location_active)
                else stringResource(R.string.weather_location_inactive),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
            )
            if (!granted) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.weather_location_grant))
                }
            }
        }
    }
}
