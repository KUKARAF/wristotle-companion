// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.history.ConversationEntry
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.sync.FileSyncCoordinator
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncFormat
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncGranularity
import com.lazydevs.wristotle.speech.nlu.settings.FileSyncSettings
import com.lazydevs.wristotle.sync.LastSyncResult
import kotlinx.coroutines.launch

/**
 * Generic Settings card driving any [FileSyncCoordinator] — Notes,
 * Conversations, and any future entity types use this same widget.
 * Only the header / description / master-toggle label change per
 * entity; everything else (folder picker, format + granularity
 * dropdowns, delete cascades, sync-now button, status line) is
 * entity-agnostic, so the strings underneath all share the
 * `file_sync_*` prefix.
 *
 * Coordinator's type parameter is erased to `<*>` at this layer
 * because nothing inside the body reads `T` — the composable only
 * touches StateFlow surfaces (`isSyncing`, `lastResult`) and
 * `suspend fun syncNow()`. Type-safety is preserved at the wrapper
 * call site ([NotesSyncCard] / [ConversationsSyncCard] take a fully
 * typed coordinator).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSyncCardBody(
    settings: FileSyncSettings,
    coordinator: FileSyncCoordinator<*>,
    @StringRes headerRes: Int,
    @StringRes descriptionRes: Int,
    @StringRes enabledLabelRes: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val enabled by settings.enabled.collectAsState()
    val folderUri by settings.folderUri.collectAsState()
    val format by settings.format.collectAsState()
    val granularity by settings.granularity.collectAsState()
    val deleteCascades by settings.deleteCascades.collectAsState()
    val isSyncing by coordinator.isSyncing.collectAsState()
    val lastResult by coordinator.lastResult.collectAsState()

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // Persist permission so it survives reboot. SAF gives us
            // one chance per pick; if takePersistableUriPermission
            // throws SecurityException the user's launcher returned a
            // non-persistable URI (rare).
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            settings.setFolderUri(uri.toString())
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitleWithInfo(
                title = stringResource(headerRes),
                description = stringResource(descriptionRes),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(enabledLabelRes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = enabled, onCheckedChange = settings::setEnabled)
            }

            if (enabled) {
                Text(
                    stringResource(R.string.file_sync_folder_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = displayNameForTreeUri(context, folderUri)
                        ?: stringResource(R.string.file_sync_folder_unpicked),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFolder.launch(null) }) {
                        Text(
                            stringResource(
                                if (folderUri.isBlank()) R.string.file_sync_pick_folder
                                else R.string.file_sync_change_folder,
                            ),
                        )
                    }
                    if (folderUri.isNotBlank()) {
                        OutlinedButton(onClick = { settings.setFolderUri("") }) {
                            Text(stringResource(R.string.file_sync_disconnect))
                        }
                    }
                }

                FormatPicker(selected = format, onSelect = settings::setFormat)
                GranularityPicker(selected = granularity, onSelect = settings::setGranularity)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.file_sync_delete_cascades_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = deleteCascades, onCheckedChange = settings::setDeleteCascades)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { coroutineScope.launch { coordinator.syncNow() } },
                        enabled = folderUri.isNotBlank() && !isSyncing,
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.file_sync_now))
                        }
                    }
                    Text(
                        text = statusLineFor(lastResult, isSyncing),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lastResult is LastSyncResult.Failed)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Notes sync card — thin wrapper passing the Notes-specific strings to
 * [FileSyncCardBody]. The coordinator parameter is typed to [Note] so
 * the call site keeps compile-time safety; the body erases it.
 */
@Composable
fun NotesSyncCard(
    settings: FileSyncSettings,
    coordinator: FileSyncCoordinator<Note>,
    modifier: Modifier = Modifier,
) = FileSyncCardBody(
    settings = settings,
    coordinator = coordinator,
    headerRes = R.string.notes_sync_header,
    descriptionRes = R.string.notes_sync_desc,
    enabledLabelRes = R.string.notes_sync_enabled_label,
    modifier = modifier,
)

/** Conversations sync card — same shape as [NotesSyncCard] with the
 *  conversation-flavoured strings swapped in. */
@Composable
fun ConversationsSyncCard(
    settings: FileSyncSettings,
    coordinator: FileSyncCoordinator<ConversationEntry>,
    modifier: Modifier = Modifier,
) = FileSyncCardBody(
    settings = settings,
    coordinator = coordinator,
    headerRes = R.string.conv_sync_header,
    descriptionRes = R.string.conv_sync_desc,
    enabledLabelRes = R.string.conv_sync_enabled_label,
    modifier = modifier,
)

@Composable
private fun statusLineFor(result: LastSyncResult?, isSyncing: Boolean): String = when {
    isSyncing -> stringResource(R.string.file_sync_in_flight)
    result is LastSyncResult.Success -> stringResource(
        R.string.file_sync_result_success, result.wrote, result.removed,
    )
    result is LastSyncResult.Failed -> stringResource(R.string.file_sync_result_failed, result.message)
    result is LastSyncResult.Skipped -> stringResource(R.string.file_sync_result_skipped)
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPicker(selected: FileSyncFormat, onSelect: (FileSyncFormat) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labelForFormat(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.file_sync_format_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FileSyncFormat.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(labelForFormat(f)) },
                    onClick = { onSelect(f); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GranularityPicker(
    selected: FileSyncGranularity,
    onSelect: (FileSyncGranularity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labelForGranularity(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.file_sync_granularity_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FileSyncGranularity.entries.forEach { g ->
                DropdownMenuItem(
                    text = { Text(labelForGranularity(g)) },
                    onClick = { onSelect(g); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun labelForFormat(f: FileSyncFormat): String = stringResource(
    when (f) {
        FileSyncFormat.Markdown -> R.string.file_sync_format_markdown
        FileSyncFormat.PlainText -> R.string.file_sync_format_plaintext
        FileSyncFormat.Json -> R.string.file_sync_format_json
    },
)

@Composable
private fun labelForGranularity(g: FileSyncGranularity): String = stringResource(
    when (g) {
        FileSyncGranularity.OneFilePerEntity -> R.string.file_sync_granularity_one_per
        FileSyncGranularity.AppendToSingleFile -> R.string.file_sync_granularity_append
    },
)

private fun displayNameForTreeUri(context: Context, folderUri: String): String? {
    if (folderUri.isBlank()) return null
    return runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.name
    }.getOrNull()
}