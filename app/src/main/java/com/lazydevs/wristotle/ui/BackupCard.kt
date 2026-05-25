package com.lazydevs.wristotle.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.backup.AudioInventory
import com.lazydevs.wristotle.backup.BackupExportResult
import com.lazydevs.wristotle.backup.BackupManifest

/**
 * Settings card for the backup / restore feature.
 *
 *   - **Export…** opens the options dialog (audio + optional password), then
 *     the system Save-As dialog. Phase A/B.
 *   - **Restore…** opens the system Open-Document dialog, then either a
 *     password prompt (encrypted ZIP) or a preview dialog (manifest stats +
 *     confirm). Phase C.
 */
@Composable
fun BackupCard(vm: BackupViewModel) {
    val isExporting by vm.isExporting.collectAsState()
    val restore by vm.restore.collectAsState()
    val exportResult by vm.exportResult.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.export(uri)
    }
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.beginRestore(uri)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_backup_header),
                description = stringResource(R.string.settings_backup_desc),
            )

            // The restore flow is also disabled while loading so the user
            // can't double-launch.
            val busy = isExporting || restore is RestoreState.Loading
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        vm.refreshAudioInventory()
                        showExportDialog = true
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        if (isExporting) stringResource(R.string.settings_backup_exporting)
                        else stringResource(R.string.settings_backup_export_button)
                    )
                }
                OutlinedButton(
                    onClick = { openDocument.launch(arrayOf("application/zip", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (restore is RestoreState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.settings_backup_restore_button))
                }
            }
        }
    }

    if (showExportDialog) {
        val audioInventory by vm.audioInventory.collectAsState()
        ExportOptionsDialog(
            audioInventory = audioInventory,
            onConfirm = { includeAudio, password ->
                showExportDialog = false
                vm.setPendingOptions(includeAudio = includeAudio, password = password)
                createDocument.launch(vm.suggestedFilename())
            },
            onDismiss = { showExportDialog = false },
        )
    }

    when (val r = exportResult) {
        is ExportResultState.Success -> ExportSuccessDialog(r.result, vm::dismissExportResult)
        is ExportResultState.Failure -> ExportFailureDialog(r.message, vm::dismissExportResult)
        ExportResultState.Idle -> Unit
    }

    when (val r = restore) {
        is RestoreState.NeedsPassword -> RestorePasswordDialog(
            wrongTried = r.wrongTried,
            onSubmit = vm::submitRestorePassword,
            onDismiss = vm::cancelRestore,
        )
        is RestoreState.Preview -> RestorePreviewDialog(
            manifest = r.manifest,
            onConfirm = vm::confirmRestore,
            onDismiss = vm::cancelRestore,
        )
        is RestoreState.Success -> RestoreSuccessDialog(
            result = r.result,
            onDismiss = vm::cancelRestore,
        )
        is RestoreState.Failure -> RestoreFailureDialog(
            message = r.message,
            onDismiss = vm::cancelRestore,
        )
        RestoreState.Idle, RestoreState.Loading -> Unit
    }
}

@Composable
private fun RestoreSuccessDialog(
    result: com.lazydevs.wristotle.backup.BackupImportResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_import_success_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_backup_import_table_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ResultRow(stringResource(R.string.settings_backup_import_row_notes), result.notes)
                ResultRow(stringResource(R.string.settings_backup_import_row_tasks), result.tasks)
                ResultRow(stringResource(R.string.settings_backup_import_row_conversations), result.conversations)
                ResultRow(stringResource(R.string.settings_backup_import_row_nlu), result.nlu)
                ResultRow(stringResource(R.string.settings_backup_import_row_audio), result.audio)
                ResultRow(stringResource(R.string.settings_backup_import_row_pins), result.pins)
                ResultRow(stringResource(R.string.settings_backup_import_row_aliases), result.aliases)
                Text(
                    stringResource(R.string.settings_backup_import_settings_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.schemaSkipped.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.settings_backup_import_schema_skipped,
                            result.schemaSkipped.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
    )
}

@Composable
private fun ResultRow(label: String, stats: com.lazydevs.wristotle.backup.EntityStats) {
    val items = stats.imported + stats.duplicates + stats.failed
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(
                R.string.settings_backup_import_row_counts,
                items, stats.imported, stats.duplicates, stats.failed,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (stats.failed > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ExportSuccessDialog(result: BackupExportResult, onDismiss: () -> Unit) {
    val kb = (result.bytes / 1024L).coerceAtLeast(1L)
    val titleRes = if (result.encrypted) R.string.settings_backup_export_success_title_encrypted
                   else R.string.settings_backup_export_success_title
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Text(
                stringResource(
                    R.string.settings_backup_export_success_body,
                    result.notes,
                    result.tasks,
                    result.conversations,
                    result.nluLearned,
                    result.audioFiles,
                    result.reminders,
                    result.aliases,
                    kb,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
        },
    )
}

@Composable
private fun ExportFailureDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_export_failed_title)) },
        text = {
            Text(
                stringResource(R.string.settings_backup_export_failed_body, message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
        },
    )
}

@Composable
private fun RestoreFailureDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_import_failed_title)) },
        text = {
            Text(
                stringResource(R.string.settings_backup_import_failed_body, message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
    )
}

@Composable
private fun ExportOptionsDialog(
    audioInventory: AudioInventory,
    onConfirm: (includeAudio: Boolean, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var includeAudio by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val audioPresent = audioInventory.files > 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            stringResource(R.string.settings_backup_include_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (audioPresent) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (audioPresent) {
                                val mb = audioInventory.bytes / 1024.0 / 1024.0
                                stringResource(
                                    R.string.settings_backup_audio_summary,
                                    audioInventory.files, mb,
                                )
                            } else {
                                stringResource(R.string.settings_backup_audio_none)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = includeAudio && audioPresent,
                        onCheckedChange = { includeAudio = it },
                        enabled = audioPresent,
                    )
                }

                PasswordField(
                    password = password,
                    onChange = { password = it },
                    visible = passwordVisible,
                    onToggleVisible = { passwordVisible = !passwordVisible },
                    label = stringResource(R.string.settings_backup_password_label),
                )
                Text(
                    stringResource(R.string.settings_backup_password_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeAudio, password) }) {
                Text(stringResource(R.string.settings_backup_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun RestorePasswordDialog(
    wrongTried: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_restore_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_backup_restore_password_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (wrongTried) {
                    Text(
                        stringResource(R.string.settings_backup_restore_password_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                PasswordField(
                    password = password,
                    onChange = { password = it },
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    label = stringResource(R.string.settings_backup_password_label),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty(),
            ) { Text(stringResource(R.string.settings_backup_restore_password_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun RestorePreviewDialog(
    manifest: BackupManifest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = manifest.stats
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_restore_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.settings_backup_restore_preview_stats,
                        s.notes, s.tasks, s.conversations, s.nluLearned, s.reminders, s.aliases,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_backup_restore_preview_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_backup_restore_preview_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Reusable masked-text field with show/hide eye toggle. */
@Composable
private fun PasswordField(
    password: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = password,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None
                               else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff
                                  else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.settings_backup_password_hide
                        else R.string.settings_backup_password_show
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
