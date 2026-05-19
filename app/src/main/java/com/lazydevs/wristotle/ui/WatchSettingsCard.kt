package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.settings.StaleReason
import com.lazydevs.wristotle.settings.WatchSettings
import com.lazydevs.wristotle.settings.WatchSettingsState

/**
 * Watch-settings mirror card. Shows a spinner while loading, the 10 controls
 * once a snapshot is available, and a hint + retry button when the most
 * recent refresh failed.
 *
 * State machine drives the body — when [WatchSettingsState.Stale] still
 * carries a previous snapshot we keep the controls visible (read-only)
 * with a banner on top, so the user isn't kicked back to "tap refresh"
 * just because the watch briefly disconnected.
 */
@Composable
fun WatchSettingsCard(vm: WatchSettingsViewModel) {
    val state by vm.state.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.watch_settings_header),
                description = stringResource(R.string.watch_settings_desc),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = vm::refresh) {
                    Text(stringResource(R.string.watch_settings_refresh))
                }
            }
            when (val s = state) {
                WatchSettingsState.Unknown -> Hint(stringResource(R.string.watch_settings_unknown))
                WatchSettingsState.Loading -> LoadingRow()
                is WatchSettingsState.Loaded -> SettingsBody(s.settings, vm)
                is WatchSettingsState.Stale -> {
                    Hint(
                        when (s.reason) {
                            StaleReason.NotConnected -> stringResource(R.string.watch_settings_disconnected)
                            StaleReason.OldWatchApp -> stringResource(R.string.watch_settings_old_app)
                        }
                    )
                    s.last?.let { SettingsBody(it, vm) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
        Text(
            stringResource(R.string.watch_settings_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsBody(settings: WatchSettings, vm: WatchSettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(
            label = stringResource(R.string.watch_settings_dictation_confirmation),
            checked = settings.dictationConfirmation,
            onChange = vm::setDictationConfirmation,
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_skip_retry_dialog),
            checked = settings.skipRetryDialog,
            onChange = vm::setSkipRetryDialog,
        )
        AutoExitDropdown(
            seconds = settings.quickLaunchAutoExitSeconds,
            onChange = vm::setQuickLaunchAutoExitSeconds,
        )

        SectionDivider(stringResource(R.string.watch_settings_vibrate_section))
        SwitchRow(
            label = stringResource(R.string.watch_settings_vibrate_on_launch),
            checked = settings.vibrateOnLaunch,
            onChange = vm::setVibrateOnLaunch,
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_vibrate_on_quick_launch),
            checked = settings.vibrateOnQuickLaunch,
            onChange = vm::setVibrateOnQuickLaunch,
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_vibrate_respect_quiet),
            checked = settings.vibrateRespectQuiet,
            onChange = vm::setVibrateRespectQuiet,
        )

        SectionDivider(stringResource(R.string.watch_settings_routing_section))
        TargetDropdown(
            label = stringResource(R.string.watch_settings_target_find_phone),
            value = settings.findPhoneTarget,
            onChange = vm::setFindPhoneTarget,
        )
        TargetDropdown(
            label = stringResource(R.string.watch_settings_target_reminders),
            value = settings.remindersTarget,
            onChange = vm::setRemindersTarget,
        )
        TargetDropdown(
            label = stringResource(R.string.watch_settings_target_cancel),
            value = settings.cancelTarget,
            onChange = vm::setCancelTarget,
        )

        SectionDivider("")
        SwitchRow(
            label = stringResource(R.string.watch_settings_logging),
            checked = settings.loggingEnabled,
            onChange = vm::setLoggingEnabled,
        )
    }
}

@Composable
private fun SectionDivider(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val AUTO_EXIT_CHOICES = listOf(0, 3, 5, 10)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoExitDropdown(seconds: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = if (seconds == 0) stringResource(R.string.watch_settings_auto_exit_off)
                    else stringResource(R.string.watch_settings_auto_exit_seconds, seconds),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.watch_settings_quick_launch_auto_exit)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AUTO_EXIT_CHOICES.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (s == 0) stringResource(R.string.watch_settings_auto_exit_off)
                            else stringResource(R.string.watch_settings_auto_exit_seconds, s)
                        )
                    },
                    onClick = {
                        onChange(s)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetDropdown(label: String, value: Int, onChange: (Int) -> Unit) {
    val choices = listOf(
        0 to stringResource(R.string.watch_settings_target_auto),
        1 to stringResource(R.string.watch_settings_target_companion),
        2 to stringResource(R.string.watch_settings_target_pkjs),
    )
    val display = choices.firstOrNull { it.first == value }?.second
        ?: stringResource(R.string.watch_settings_target_auto)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (v, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onChange(v)
                        expanded = false
                    },
                )
            }
        }
    }
}
