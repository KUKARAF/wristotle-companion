package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.alarms.AlarmDestination
import com.lazydevs.wristotle.alarms.AlarmDispatcher
import com.lazydevs.wristotle.alarms.AlarmEntity
import com.lazydevs.wristotle.alarms.AlarmRepository
import com.lazydevs.wristotle.alarms.AlarmSettings
import com.lazydevs.wristotle.alarms.DispatchResult
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → ⏰ Alarms & Reminders → Alarms card.
 *
 * List + create + delete + enabled-toggle for [AlarmEntity] rows. Edits
 * fan out via [AlarmDispatcher]: enabling re-fires both legs (phone leg
 * via AlarmClock intent, watch leg via PebbleTransport), disabling
 * cancels the watch leg only (phone is a programmatic dead-end per
 * alarm-timer.md).
 *
 * Pairs with [ReminderSettingsCard] under the same category drill-down.
 */
@Composable
fun AlarmsCard(
    repository: AlarmRepository,
    dispatcher: AlarmDispatcher,
    settings: AlarmSettings,
) {
    val alarms by repository.observeAll().collectAsState(initial = emptyList())
    val defaultDestination by settings.defaultDestination.collectAsState()
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<List<String>?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.alarms_card_header),
                description = stringResource(R.string.alarms_card_desc),
            )

            DefaultDestinationDropdown(
                selected = defaultDestination,
                onSelect = settings::setDefaultDestination,
            )

            if (alarms.isEmpty()) {
                Text(
                    stringResource(R.string.alarms_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                alarms.forEachIndexed { index, alarm ->
                    if (index > 0) HorizontalDivider()
                    AlarmRow(
                        alarm = alarm,
                        onToggle = { enable ->
                            scope.launch {
                                if (enable) {
                                    val refreshed = alarm.copy(enabled = true)
                                    repository.update(refreshed)
                                    val result = dispatcher.schedule(refreshed)
                                    if (result is DispatchResult.PartialFailure) {
                                        feedback = result.errors
                                    }
                                } else {
                                    dispatcher.cancelWatchLeg(alarm)
                                    repository.update(alarm.copy(enabled = false, wireEpoch = null))
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                if (alarm.wireEpoch != null) dispatcher.cancelWatchLeg(alarm)
                                repository.delete(alarm.id)
                            }
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showEditor = true }) {
                    Text(stringResource(R.string.alarms_add_button))
                }
            }
        }
    }

    if (showEditor) {
        AlarmEditorDialog(
            initialDestination = defaultDestination,
            onDismiss = { showEditor = false },
            onSave = { hour, minute, label, destination ->
                showEditor = false
                scope.launch {
                    val now = System.currentTimeMillis()
                    val draft = AlarmEntity(
                        hour = hour,
                        minute = minute,
                        label = label,
                        destination = destination.name,
                        wireEpoch = null,
                        enabled = true,
                        createdAtEpochMs = now,
                    )
                    val id = repository.insert(draft)
                    val saved = repository.getById(id) ?: draft.copy(id = id)
                    val result = dispatcher.schedule(saved)
                    if (result is DispatchResult.PartialFailure) {
                        feedback = result.errors
                    }
                }
            },
        )
    }

    feedback?.let { errors ->
        AlertDialog(
            onDismissRequest = { feedback = null },
            confirmButton = {
                TextButton(onClick = { feedback = null }) {
                    Text(stringResource(R.string.alarms_dismiss))
                }
            },
            title = { Text(stringResource(R.string.alarms_card_header)) },
            text = {
                Text(stringResource(R.string.alarms_save_partial, errors.joinToString("\n")))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultDestinationDropdown(
    selected: AlarmDestination,
    onSelect: (AlarmDestination) -> Unit,
) {
    val options = listOf(
        AlarmDestination.Phone to stringResource(R.string.alarms_destination_phone),
        AlarmDestination.Watch to stringResource(R.string.alarms_destination_watch),
        AlarmDestination.Both  to stringResource(R.string.alarms_destination_both),
    )
    val display = options.firstOrNull { it.first == selected }?.second
        ?: stringResource(R.string.alarms_destination_phone)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.alarms_default_destination_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (dest, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(dest); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: AlarmEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                formatClock(alarm.hour, alarm.minute),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(destinationLabel(alarm.destination))
                    if (alarm.label.isNotBlank()) append(" · ").append(alarm.label)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.alarms_delete_button))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditorDialog(
    initialDestination: AlarmDestination,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int, label: String, destination: AlarmDestination) -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = 7, initialMinute = 0)
    var label by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf(initialDestination) }

    // Use a raw Dialog + Surface so the content gets its full natural
    // height (AlertDialog caps the text slot, which clipped the bottom
    // rows when TimePicker was on top). The Column scrolls if it exceeds
    // the screen on a very short device.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.alarms_editor_title),
                    style = MaterialTheme.typography.headlineSmall,
                )

                // TimeInput is the compact digital entry (HH MM text
                // fields). The analog TimePicker dial was eating ~280 dp
                // and squishing everything below it.
                TimeInput(state = timeState)

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.alarms_editor_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(R.string.alarms_editor_destination_header),
                    style = MaterialTheme.typography.labelLarge,
                )
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DestinationOption(
                        label = stringResource(R.string.alarms_destination_phone),
                        selected = destination == AlarmDestination.Phone,
                        onSelect = { destination = AlarmDestination.Phone },
                    )
                    DestinationOption(
                        label = stringResource(R.string.alarms_destination_watch),
                        selected = destination == AlarmDestination.Watch,
                        onSelect = { destination = AlarmDestination.Watch },
                    )
                    DestinationOption(
                        label = stringResource(R.string.alarms_destination_both),
                        selected = destination == AlarmDestination.Both,
                        onSelect = { destination = AlarmDestination.Both },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.alarms_editor_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(timeState.hour, timeState.minute, label.trim(), destination)
                    }) {
                        Text(stringResource(R.string.alarms_editor_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    // No fixed height — let the row size to RadioButton + Text content.
    // The previous .height(40.dp) was clipping the row in the tight
    // AlertDialog slot, causing visual overlap.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun destinationLabel(destinationName: String): String =
    when (runCatching { AlarmDestination.valueOf(destinationName) }.getOrNull()) {
        AlarmDestination.Phone -> stringResource(R.string.alarms_dest_label_phone)
        AlarmDestination.Watch -> stringResource(R.string.alarms_dest_label_watch)
        AlarmDestination.Both  -> stringResource(R.string.alarms_dest_label_both)
        null -> destinationName
    }

private fun formatClock(hour: Int, minute: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(h12, minute, period)
}
