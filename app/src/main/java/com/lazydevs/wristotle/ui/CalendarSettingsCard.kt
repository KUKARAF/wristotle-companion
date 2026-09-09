// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.phone.CalendarRepository
import com.lazydevs.wristotle.speech.nlu.settings.CalendarSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → 📅 Calendar. Lets the user pick which calendar voice-created
 * events land in, instead of the app auto-picking the primary. "Automatic"
 * keeps the old behaviour. Enumerates writable calendars via
 * [CalendarRepository.listCalendars]; needs the calendar read permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsCard(settings: CalendarSettings) {
    val context = LocalContext.current
    val targetId by settings.targetCalendarId.collectAsState()
    var calendars by remember { mutableStateOf<List<CalendarRepository.CalendarInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        calendars = withContext(Dispatchers.IO) { CalendarRepository(context).listCalendars() }
        loaded = true
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.calendar_target_header),
                description = stringResource(R.string.calendar_target_desc),
            )

            if (loaded && calendars.isEmpty()) {
                Text(
                    stringResource(R.string.calendar_target_no_calendars),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val autoLabel = stringResource(R.string.calendar_target_auto)
            val selected = calendars.firstOrNull { it.id == targetId }
            val selectedLabel = when {
                targetId == CalendarSettings.AUTOMATIC -> autoLabel
                selected != null -> labelFor(selected)
                else -> stringResource(R.string.calendar_target_unavailable)
            }

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.calendar_target_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(autoLabel) },
                        onClick = {
                            settings.setTargetCalendarId(CalendarSettings.AUTOMATIC)
                            expanded = false
                        },
                    )
                    calendars.forEach { cal ->
                        DropdownMenuItem(
                            text = { Text(labelFor(cal)) },
                            onClick = {
                                settings.setTargetCalendarId(cal.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun labelFor(cal: CalendarRepository.CalendarInfo): String {
    val base = if (cal.accountName.isNotBlank() && cal.accountName != cal.displayName) {
        "${cal.displayName} (${cal.accountName})"
    } else {
        cal.displayName
    }
    return if (cal.isPrimary) "$base ★" else base
}
