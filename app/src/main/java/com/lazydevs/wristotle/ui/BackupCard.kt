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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.backup.AudioInventory

/**
 * Settings card for the backup / restore feature.
 *
 *   - **Export…** opens a small options dialog (audio inclusion + optional
 *     password) and then the system Save-As dialog (`CreateDocument`) so
 *     the user can drop the ZIP in Drive / OneDrive / Files / wherever.
 *   - **Restore…** is shown but disabled in Phase B; it lights up in
 *     Phase C when the importer lands.
 */
@Composable
fun BackupCard(vm: BackupViewModel) {
    val isExporting by vm.isExporting.collectAsState()
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.export(uri)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val message = when (event) {
                is BackupEvent.ExportDone -> {
                    val kb = (event.result.bytes / 1024L).coerceAtLeast(1L)
                    val key = if (event.result.encrypted)
                        R.string.settings_backup_export_success_encrypted
                    else
                        R.string.settings_backup_export_success
                    context.getString(
                        key,
                        event.result.notes,
                        event.result.conversations,
                        event.result.audioFiles,
                        kb,
                    )
                }
                is BackupEvent.ExportFailed ->
                    context.getString(R.string.settings_backup_export_failed, event.message)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        vm.refreshAudioInventory()
                        showDialog = true
                    },
                    enabled = !isExporting,
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
                // Restore is disabled in Phase B — lights up in Phase C.
                OutlinedButton(
                    onClick = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_backup_restore_coming_soon),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_restore_button))
                }
            }
        }
    }

    if (showDialog) {
        val audioInventory by vm.audioInventory.collectAsState()
        ExportOptionsDialog(
            audioInventory = audioInventory,
            onConfirm = { includeAudio, password ->
                showDialog = false
                vm.setPendingOptions(includeAudio = includeAudio, password = password)
                createDocument.launch(vm.suggestedFilename())
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Pre-export dialog. Captures two user choices:
 *   - Whether to include the conversation + notes audio (.wav) files.
 *   - An optional password — non-empty triggers AES-256 ZIP encryption.
 *
 * Dialog state lives locally; the choices are handed back through
 * [onConfirm] so the parent VM can record them before launching SAF.
 */
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
                // Audio inclusion row — label + summary + switch. Disabled
                // when there are no audio files to include (nothing to do).
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

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_backup_password_label)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                              else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.settings_backup_password_hide
                                    else R.string.settings_backup_password_show
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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
