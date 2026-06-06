package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
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
 * Watch-settings mirror card.
 *
 * Edits are staged in a local draft and only written to the watch when the
 * user taps **Save** — no auto-save on each toggle. When the Settings page is
 * left the section resets to [WatchSettingsState.Unknown] (via the
 * [DisposableEffect] below), so it always re-fetches a fresh snapshot on the
 * next visit rather than showing possibly-stale values.
 */
@Composable
fun WatchSettingsCard(vm: WatchSettingsViewModel) {
    val state by vm.state.collectAsState()

    // Hide + drop the cached snapshot when this card leaves composition
    // (i.e. the user switches away from the Settings tab). Forces a fresh
    // Refresh next visit.
    DisposableEffect(Unit) {
        onDispose { vm.reset() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.watch_settings_header),
                description = stringResource(R.string.watch_settings_desc),
            )
            Text(
                stringResource(R.string.watch_settings_open_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                is WatchSettingsState.Loaded -> EditableBody(s.settings, vm::save)
                is WatchSettingsState.Stale -> {
                    Hint(
                        when (s.reason) {
                            StaleReason.NotConnected -> stringResource(R.string.watch_settings_disconnected)
                            StaleReason.OldWatchApp -> stringResource(R.string.watch_settings_old_app)
                        }
                    )
                    s.last?.let { EditableBody(it, vm::save) }
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

/**
 * Renders the 10 controls bound to a local [draft], seeded from [baseline].
 * Save is enabled only while the draft differs from the baseline; tapping it
 * commits via [onSave]. The draft re-seeds whenever [baseline] changes (a new
 * Refresh snapshot, or the optimistic post-save update).
 */
@Composable
private fun EditableBody(baseline: WatchSettings, onSave: (WatchSettings) -> Unit) {
    var draft by remember(baseline) { mutableStateOf(baseline) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(
            label = stringResource(R.string.watch_settings_color_theme),
            checked = draft.colorTheme,
            onChange = { draft = draft.copy(colorTheme = it) },
        )
        Text(
            stringResource(R.string.watch_settings_color_theme_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_dictation_confirmation),
            checked = draft.dictationConfirmation,
            onChange = { draft = draft.copy(dictationConfirmation = it) },
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_confirm_before_send),
            checked = draft.confirmBeforeSend,
            onChange = { draft = draft.copy(confirmBeforeSend = it) },
        )
        // The two sub-settings only matter when the main toggle is on —
        // hide them otherwise to keep the card focused. Cheap reactive
        // hide via `if (draft.confirmBeforeSend)`; the draft re-seeds on
        // each Refresh so flipping the parent toggle off → on → off
        // doesn't lose the sub-values (they live in the WatchSettings
        // record either way).
        if (draft.confirmBeforeSend) {
            ConfirmTimeoutDropdown(
                seconds = draft.confirmTimeoutSeconds,
                onChange = { draft = draft.copy(confirmTimeoutSeconds = it) },
            )
            SwitchRow(
                label = stringResource(R.string.watch_settings_confirm_default_send),
                checked = draft.confirmDefaultSend,
                onChange = { draft = draft.copy(confirmDefaultSend = it) },
            )
        }
        SwitchRow(
            label = stringResource(R.string.watch_settings_skip_retry_dialog),
            checked = draft.skipRetryDialog,
            onChange = { draft = draft.copy(skipRetryDialog = it) },
        )
        AutoExitDropdown(
            seconds = draft.quickLaunchAutoExitSeconds,
            onChange = { draft = draft.copy(quickLaunchAutoExitSeconds = it) },
        )
        QuickLaunchActionDropdown(
            value = draft.quickLaunchAction,
            onChange = { draft = draft.copy(quickLaunchAction = it) },
        )

        SectionDivider(stringResource(R.string.watch_settings_shortcuts_section))
        Text(
            stringResource(R.string.watch_settings_shortcuts_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ButtonActionDropdown(
            label = stringResource(R.string.watch_settings_button_select),
            value = draft.selectAction,
            onChange = { draft = draft.copy(selectAction = it) },
        )
        ButtonActionDropdown(
            label = stringResource(R.string.watch_settings_button_up),
            value = draft.longPressUpAction,
            onChange = { draft = draft.copy(longPressUpAction = it) },
        )
        ButtonActionDropdown(
            label = stringResource(R.string.watch_settings_button_down),
            value = draft.longPressDownAction,
            onChange = { draft = draft.copy(longPressDownAction = it) },
        )

        SectionDivider(stringResource(R.string.watch_settings_vibrate_section))
        SwitchRow(
            label = stringResource(R.string.watch_settings_vibrate_on_launch),
            checked = draft.vibrateOnLaunch,
            onChange = { draft = draft.copy(vibrateOnLaunch = it) },
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_vibrate_on_quick_launch),
            checked = draft.vibrateOnQuickLaunch,
            onChange = { draft = draft.copy(vibrateOnQuickLaunch = it) },
        )
        SwitchRow(
            label = stringResource(R.string.watch_settings_vibrate_respect_quiet),
            checked = draft.vibrateRespectQuiet,
            onChange = { draft = draft.copy(vibrateRespectQuiet = it) },
        )

        SectionDivider(stringResource(R.string.watch_settings_routing_section))
        TargetDropdown(
            label = stringResource(R.string.watch_settings_target_find_phone),
            value = draft.findPhoneTarget,
            onChange = { draft = draft.copy(findPhoneTarget = it) },
        )
        TargetDropdown(
            label = stringResource(R.string.watch_settings_target_reminders),
            value = draft.remindersTarget,
            onChange = { draft = draft.copy(remindersTarget = it) },
        )
        TargetDropdown(
            label = stringResource(R.string.watch_settings_target_cancel),
            value = draft.cancelTarget,
            onChange = { draft = draft.copy(cancelTarget = it) },
        )

        SectionDivider("")
        SwitchRow(
            label = stringResource(R.string.watch_settings_logging),
            checked = draft.loggingEnabled,
            onChange = { draft = draft.copy(loggingEnabled = it) },
        )

        Button(
            onClick = { onSave(draft) },
            enabled = draft != baseline,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.watch_settings_save))
        }
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

// Mirrors the Clay options exactly so the picker round-trips with the
// watch's whitelist. 0 maps to "Never" (no timer armed).
private val CONFIRM_TIMEOUT_CHOICES = listOf(5, 10, 15, 30, 60, 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmTimeoutDropdown(seconds: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = confirmTimeoutLabel(seconds)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.watch_settings_confirm_timeout)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CONFIRM_TIMEOUT_CHOICES.forEach { s ->
                DropdownMenuItem(
                    text = { Text(confirmTimeoutLabel(s)) },
                    onClick = { onChange(s); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun confirmTimeoutLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.watch_settings_confirm_timeout_never)
    60 -> stringResource(R.string.watch_settings_confirm_timeout_minute)
    else -> stringResource(R.string.watch_settings_confirm_timeout_seconds, seconds)
}

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
private fun QuickLaunchActionDropdown(value: Int, onChange: (Int) -> Unit) {
    val choices = listOf(
        com.lazydevs.wristotle.transport.MessageKeys.QUICK_LAUNCH_ACTION_DICTATE
            to stringResource(R.string.watch_settings_quick_launch_action_dictate),
        com.lazydevs.wristotle.transport.MessageKeys.QUICK_LAUNCH_ACTION_NOTES
            to stringResource(R.string.watch_settings_quick_launch_action_notes),
        com.lazydevs.wristotle.transport.MessageKeys.QUICK_LAUNCH_ACTION_MENU
            to stringResource(R.string.watch_settings_quick_launch_action_menu),
    )
    val display = choices.firstOrNull { it.first == value }?.second
        ?: stringResource(R.string.watch_settings_quick_launch_action_dictate)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.watch_settings_quick_launch_action)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (v, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onChange(v); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonActionDropdown(label: String, value: Int, onChange: (Int) -> Unit) {
    val keys = com.lazydevs.wristotle.transport.MessageKeys
    val choices = listOf(
        keys.BUTTON_ACTION_DICTATION to stringResource(R.string.watch_settings_button_action_dictation),
        keys.BUTTON_ACTION_MENU      to stringResource(R.string.watch_settings_button_action_menu),
        keys.BUTTON_ACTION_NOTES     to stringResource(R.string.watch_settings_button_action_notes),
        keys.BUTTON_ACTION_TASKS     to stringResource(R.string.watch_settings_button_action_tasks),
        keys.BUTTON_ACTION_ALARMS    to stringResource(R.string.watch_settings_button_action_alarms),
    )
    val display = choices.firstOrNull { it.first == value }?.second
        ?: stringResource(R.string.watch_settings_button_action_menu)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
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
                    onClick = { onChange(v); expanded = false },
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
