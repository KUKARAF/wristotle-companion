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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.nlu.settings.AppendAudioMode
import com.lazydevs.wristotle.speech.nlu.settings.NoteSettings

/**
 * Settings card exposing the notes keep-last-N cap. "All" (the default)
 * means no cap; the other presets FIFO-prune the oldest beyond N.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSettingsCard(vm: NotesViewModel, modifier: Modifier = Modifier) {
    val current by vm.keepLast.collectAsState()
    val audioMode by vm.appendAudioMode.collectAsState()
    var keepExpanded by remember { mutableStateOf(false) }
    var audioExpanded by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitleWithInfo(
                title = stringResource(R.string.notes_settings_header),
                description = stringResource(R.string.notes_settings_desc),
            )

            ExposedDropdownMenuBox(expanded = keepExpanded, onExpandedChange = { keepExpanded = it }) {
                OutlinedTextField(
                    value = labelFor(current),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.notes_settings_keep_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keepExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = keepExpanded, onDismissRequest = { keepExpanded = false }) {
                    vm.keepLastOptions.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(labelFor(value)) },
                            onClick = { vm.setKeepLast(value); keepExpanded = false },
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(expanded = audioExpanded, onExpandedChange = { audioExpanded = it }) {
                OutlinedTextField(
                    value = labelForMode(audioMode),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.notes_settings_append_audio_label)) },
                    supportingText = { Text(stringResource(R.string.notes_settings_append_audio_desc)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = audioExpanded, onDismissRequest = { audioExpanded = false }) {
                    AppendAudioMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(labelForMode(mode)) },
                            onClick = { vm.setAppendAudioMode(mode); audioExpanded = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun labelForMode(mode: AppendAudioMode): String = stringResource(
    when (mode) {
        AppendAudioMode.MERGE -> R.string.notes_settings_append_audio_merge
        AppendAudioMode.SEPARATE -> R.string.notes_settings_append_audio_separate
    },
)

@Composable
private fun labelFor(value: Int): String =
    if (value == NoteSettings.UNLIMITED) stringResource(R.string.notes_settings_keep_all)
    else stringResource(R.string.notes_settings_keep_count, value)