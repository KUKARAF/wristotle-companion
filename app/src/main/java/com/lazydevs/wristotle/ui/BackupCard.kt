// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.backup.BackupCounts
import com.lazydevs.wristotle.backup.BackupExportResult
import com.lazydevs.wristotle.backup.BackupManifest
import com.lazydevs.wristotle.speech.nlu.backup.BackupSelection
import com.lazydevs.wristotle.ui.components.ConfirmDialog
import com.lazydevs.wristotle.ui.components.InfoDialog
import com.lazydevs.wristotle.ui.components.PasswordField

/**
 * Settings card for the backup / restore feature.
 *
 *   - **Export…** opens the options dialog (per-category checkboxes
 *     for what to include + optional encryption password), then the
 *     system Save-As dialog.
 *   - **Restore…** opens the system Open-Document dialog, then either
 *     a password prompt (encrypted ZIP) or a preview dialog with the
 *     same per-category checkbox tree (ticks default to what's in the
 *     ZIP; categories not in the ZIP are disabled).
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

            val busy = isExporting || restore is RestoreState.Loading
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        vm.refreshExportCounts()
                        showExportDialog = true
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(16.dp),
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
                            modifier = Modifier.padding(end = 8.dp).size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.settings_backup_restore_button))
                }
            }
        }
    }

    // Two-stage commit on Export: the options dialog returns a (selection,
    // password) pair, then — if secrets are ticked AND password is blank —
    // a "plaintext secrets?" confirm dialog blocks the SAF launch until
    // the user explicitly acknowledges the plaintext-leak risk. The flag
    // is a Boolean (not the password string) because the only branch that
    // reaches this stash already proved the password is blank.
    var showPlaintextConfirm by remember { mutableStateOf(false) }
    if (showExportDialog) {
        val options by vm.exportOptions.collectAsState()
        val counts by vm.exportCounts.collectAsState()
        ExportOptionsDialog(
            selection = options.selection,
            counts = counts,
            onSelectionChange = vm::setExportSelection,
            onSelectAll = vm::toggleExportSelectAll,
            onConfirm = { password ->
                showExportDialog = false
                if (options.selection.anySecretSelected && password.isBlank()) {
                    showPlaintextConfirm = true
                } else {
                    vm.setPendingPassword(password)
                    createDocument.launch(vm.suggestedFilename())
                }
            },
            onDismiss = { showExportDialog = false },
        )
    }

    if (showPlaintextConfirm) {
        PlaintextSecretsConfirmDialog(
            onProceed = {
                showPlaintextConfirm = false
                vm.setPendingPassword("")
                createDocument.launch(vm.suggestedFilename())
            },
            onCancel = {
                showPlaintextConfirm = false
                showExportDialog = true   // back to the options dialog
            },
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
            available = r.available,
            selection = r.restoreSelection,
            onSelectionChange = vm::setRestoreSelection,
            onSelectAll = vm::toggleRestoreSelectAll,
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

// ── Selection editor (shared by export + restore) ────────────────────────────

/**
 * The per-category checkbox tree. Used twice:
 *  - Export: every box editable, no `available` clamp.
 *  - Restore: `available` populated from `manifest.selected`; categories
 *    not in the ZIP are rendered disabled with a "— not in this backup"
 *    suffix.
 */
@Composable
private fun BackupSelectionEditor(
    selection: BackupSelection,
    available: BackupSelection?,
    counts: BackupCounts,
    onChange: (BackupSelection) -> Unit,
    onSelectAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Master row's checked state:
        //   - Export side (available == null): mirror "all content + settings
        //     ticked". Using `allSelected` here would require secrets too,
        //     and since secrets default OFF the master would lie about
        //     state on first open.
        //   - Restore side: "ticked" means the user wants everything that's
        //     in the ZIP — i.e. the full available set.
        val masterChecked = remember(selection, available) {
            if (available == null) selection.allContentAndSettingsSelected
            else selection == available
        }
        CheckRow(
            label = stringResource(R.string.settings_backup_select_all),
            checked = masterChecked,
            enabled = true,
            onCheckedChange = { onSelectAll() },
            isBold = true,
        )

        SectionHeader(stringResource(R.string.settings_backup_section_content))
        CatRow(R.string.settings_backup_cat_notes, selection.notes, available?.notes, counts.notes) {
            onChange(selection.copy(notes = it))
        }
        CatRow(R.string.settings_backup_cat_codes, selection.codes, available?.codes, counts.codes) {
            onChange(selection.copy(codes = it))
        }
        CatRow(R.string.settings_backup_cat_tasks, selection.tasks, available?.tasks, counts.tasks) {
            onChange(selection.copy(tasks = it))
        }
        CatRow(R.string.settings_backup_cat_conversations, selection.conversations, available?.conversations, counts.conversations) {
            onChange(selection.copy(conversations = it))
        }
        CatRow(R.string.settings_backup_cat_reminders, selection.reminders, available?.reminders, counts.reminders) {
            onChange(selection.copy(reminders = it))
        }
        CatRow(R.string.settings_backup_cat_nlu_learned, selection.nluLearned, available?.nluLearned, counts.nluLearned) {
            onChange(selection.copy(nluLearned = it))
        }
        CatRow(R.string.settings_backup_cat_app_aliases, selection.appAliases, available?.appAliases, counts.appAliases) {
            onChange(selection.copy(appAliases = it))
        }
        CatRow(R.string.settings_backup_cat_contact_aliases, selection.contactAliases, available?.contactAliases, counts.contactAliases) {
            onChange(selection.copy(contactAliases = it))
        }
        CatRow(
            labelRes = R.string.settings_backup_cat_audio_recordings,
            checked = selection.audioRecordings,
            available = available?.audioRecordings,
            count = counts.audioRecordings,
            audioBytes = counts.audioBytes,
            onCheckedChange = { onChange(selection.copy(audioRecordings = it)) },
        )

        SectionHeader(stringResource(R.string.settings_backup_section_settings))
        CatRow(R.string.settings_backup_cat_app_preferences, selection.appPreferences, available?.appPreferences) {
            onChange(selection.copy(appPreferences = it))
        }
        CatRow(R.string.settings_backup_cat_weather_settings, selection.weatherSettings, available?.weatherSettings) {
            onChange(selection.copy(weatherSettings = it))
        }
        CatRow(R.string.settings_backup_cat_mcp_servers, selection.mcpServers, available?.mcpServers, counts.mcpServers) {
            onChange(selection.copy(mcpServers = it))
        }
        CatRow(R.string.settings_backup_cat_ask_agent_setup, selection.askAgentSetup, available?.askAgentSetup) {
            onChange(selection.copy(askAgentSetup = it))
        }
        CatRow(R.string.settings_backup_cat_stt_provider_setup, selection.sttProviderSetup, available?.sttProviderSetup) {
            onChange(selection.copy(sttProviderSetup = it))
        }
        CatRow(R.string.settings_backup_cat_tts_provider_setup, selection.ttsProviderSetup, available?.ttsProviderSetup) {
            onChange(selection.copy(ttsProviderSetup = it))
        }
        CatRow(R.string.settings_backup_cat_sport_settings, selection.sportSettings, available?.sportSettings) {
            onChange(selection.copy(sportSettings = it))
        }

        SectionHeader(stringResource(R.string.settings_backup_section_secrets))
        Text(
            stringResource(R.string.settings_backup_section_secrets_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
        CatRow(R.string.settings_backup_cat_weather_api_key, selection.weatherApiKey, available?.weatherApiKey) {
            onChange(selection.copy(weatherApiKey = it))
        }
        CatRow(R.string.settings_backup_cat_mcp_auth_headers, selection.mcpAuthHeaders, available?.mcpAuthHeaders) {
            onChange(selection.copy(mcpAuthHeaders = it))
        }
        CatRow(R.string.settings_backup_cat_ask_agent_api_keys, selection.askAgentApiKeys, available?.askAgentApiKeys) {
            onChange(selection.copy(askAgentApiKeys = it))
        }
        CatRow(R.string.settings_backup_cat_stt_provider_api_key, selection.sttProviderApiKey, available?.sttProviderApiKey) {
            onChange(selection.copy(sttProviderApiKey = it))
        }
        CatRow(R.string.settings_backup_cat_tts_provider_api_key, selection.ttsProviderApiKey, available?.ttsProviderApiKey) {
            onChange(selection.copy(ttsProviderApiKey = it))
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 2.dp),
    )
}

/**
 * One row in the checkbox tree. When `available` is non-null and false,
 * the row is disabled (category isn't in the ZIP) and the label gets a
 * "— not in this backup" suffix. When [count] is non-null, the label is
 * suffixed with "(N)"; when [audioBytes] is also non-null it becomes
 * "(N · M.M MB)".
 */
@Composable
private fun CatRow(
    labelRes: Int,
    checked: Boolean,
    available: Boolean?,
    count: Int? = null,
    audioBytes: Long? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val enabled = available ?: true
    val base = stringResource(labelRes)
    // Derived label depends on the static base + the two dynamic counts +
    // whether the category is in the ZIP. Memoizing avoids re-evaluating
    // the stringResource lookups on every parent recomposition (the editor
    // sits inside a dialog that recomposes the whole tree on each tick).
    val countWithSizeFormat = stringResource(R.string.settings_backup_cat_count_with_size)
    val countFormat = stringResource(R.string.settings_backup_cat_count)
    val unavailableSuffix = stringResource(R.string.settings_backup_restore_unavailable)
    val locale = java.util.Locale.getDefault()
    val label = remember(base, count, audioBytes, available, countWithSizeFormat, countFormat, unavailableSuffix) {
        val withCount = when {
            count == null -> base
            audioBytes != null -> {
                val mb = audioBytes / 1024.0 / 1024.0
                String.format(locale, countWithSizeFormat, base, count, mb)
            }
            else -> String.format(locale, countFormat, base, count)
        }
        if (available == false) "$withCount $unavailableSuffix" else withCount
    }
    CheckRow(label = label, checked = checked && enabled, enabled = enabled, onCheckedChange = onCheckedChange)
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isBold: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.let {
                if (isBold) it.copy(fontWeight = FontWeight.SemiBold) else it
            },
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Export dialog ────────────────────────────────────────────────────────────

@Composable
private fun ExportOptionsDialog(
    selection: BackupSelection,
    counts: BackupCounts,
    onSelectionChange: (BackupSelection) -> Unit,
    onSelectAll: () -> Unit,
    onConfirm: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupSelectionEditor(
                    selection = selection,
                    available = null,
                    counts = counts,
                    onChange = onSelectionChange,
                    onSelectAll = onSelectAll,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                PasswordField(
                    value = password,
                    onChange = { password = it },
                    label = stringResource(R.string.settings_backup_password_label),
                    visible = passwordVisible,
                    onToggleVisible = { passwordVisible = !passwordVisible },
                )
                Text(
                    stringResource(R.string.settings_backup_password_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = !selection.noneSelected,
            ) { Text(stringResource(R.string.settings_backup_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

// ── Restore dialogs ──────────────────────────────────────────────────────────

@Composable
private fun RestorePreviewDialog(
    manifest: BackupManifest,
    available: BackupSelection,
    selection: BackupSelection,
    onSelectionChange: (BackupSelection) -> Unit,
    onSelectAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = manifest.stats
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_restore_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.settings_backup_restore_preview_stats,
                        s.notes, s.tasks, s.conversations, s.nluLearned, s.reminders,
                        s.aliases, s.contactAliases, s.mcpServers,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BackupSelectionEditor(
                    selection = selection,
                    available = available,
                    counts = BackupCounts.fromManifestStats(manifest.stats),
                    onChange = onSelectionChange,
                    onSelectAll = onSelectAll,
                )
                Text(
                    stringResource(R.string.settings_backup_restore_preview_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !selection.noneSelected,
            ) { Text(stringResource(R.string.settings_backup_restore_preview_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
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
                    value = password,
                    onChange = { password = it },
                    label = stringResource(R.string.settings_backup_password_label),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
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
private fun RestoreSuccessDialog(
    result: com.lazydevs.wristotle.backup.BackupImportResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_import_success_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_backup_import_table_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ResultRow(stringResource(R.string.settings_backup_import_row_notes), result.notes)
                ResultRow(stringResource(R.string.settings_backup_import_row_codes), result.codes)
                ResultRow(stringResource(R.string.settings_backup_import_row_tasks), result.tasks)
                ResultRow(stringResource(R.string.settings_backup_import_row_conversations), result.conversations)
                ResultRow(stringResource(R.string.settings_backup_import_row_nlu), result.nlu)
                ResultRow(stringResource(R.string.settings_backup_import_row_mcp_servers), result.mcpServers)
                ResultRow(stringResource(R.string.settings_backup_import_row_audio), result.audio)
                ResultRow(stringResource(R.string.settings_backup_import_row_pins), result.pins)
                ResultRow(stringResource(R.string.settings_backup_import_row_aliases), result.aliases)
                ResultRow(stringResource(R.string.settings_backup_import_row_contact_aliases), result.contactAliases)
                BackupSelectionSections(selection = result.selection, isExport = false)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.settings_backup_export_success_body,
                        result.notes, result.tasks, result.conversations, result.nluLearned,
                        result.audioFiles, result.reminders, result.aliases,
                        result.contactAliases, result.mcpServers, kb,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BackupSelectionSections(selection = result.selection, isExport = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
        },
    )
}

@Composable
private fun ExportFailureDialog(message: String, onDismiss: () -> Unit) {
    InfoDialog(
        title = stringResource(R.string.settings_backup_export_failed_title),
        message = stringResource(R.string.settings_backup_export_failed_body, message),
        onDismiss = onDismiss,
    )
}

@Composable
private fun RestoreFailureDialog(message: String, onDismiss: () -> Unit) {
    InfoDialog(
        title = stringResource(R.string.settings_backup_import_failed_title),
        message = stringResource(R.string.settings_backup_import_failed_body, message),
        onDismiss = onDismiss,
    )
}

@Composable
private fun PlaintextSecretsConfirmDialog(
    onProceed: () -> Unit,
    onCancel: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.settings_backup_plaintext_secrets_title),
        message = stringResource(R.string.settings_backup_plaintext_secrets_body),
        confirmLabel = stringResource(R.string.settings_backup_plaintext_secrets_proceed),
        onConfirm = onProceed,
        onDismiss = onCancel,
    )
}

/**
 * "Settings included / Settings applied" + optional "Secrets included /
 * Secrets applied" sections for the export + restore summary dialogs.
 * The entity rows above already cover Notes / Tasks / etc — this fills
 * the gap for settings + secrets categories the user can't see in the
 * count rows.
 *
 * Empty sections are dropped entirely so a user who unticked every
 * settings category doesn't see a stray "Settings applied:" header.
 */
@Composable
private fun BackupSelectionSections(selection: BackupSelection, isExport: Boolean) {
    val settings = listOfNotNull(
        if (selection.appPreferences) R.string.settings_backup_summary_cat_app_preferences else null,
        if (selection.weatherSettings) R.string.settings_backup_summary_cat_weather_settings else null,
        if (selection.mcpServers) R.string.settings_backup_summary_cat_mcp_servers else null,
        if (selection.askAgentSetup) R.string.settings_backup_summary_cat_ask_agent_setup else null,
        if (selection.sttProviderSetup) R.string.settings_backup_summary_cat_stt_provider_setup else null,
        if (selection.ttsProviderSetup) R.string.settings_backup_summary_cat_tts_provider_setup else null,
        if (selection.sportSettings) R.string.settings_backup_summary_cat_sport_settings else null,
    )
    val secrets = listOfNotNull(
        if (selection.weatherApiKey) R.string.settings_backup_summary_cat_weather_api_key else null,
        if (selection.mcpAuthHeaders) R.string.settings_backup_summary_cat_mcp_auth_headers else null,
        if (selection.askAgentApiKeys) R.string.settings_backup_summary_cat_ask_agent_api_keys else null,
        if (selection.sttProviderApiKey) R.string.settings_backup_summary_cat_stt_provider_api_key else null,
        if (selection.ttsProviderApiKey) R.string.settings_backup_summary_cat_tts_provider_api_key else null,
    )

    if (settings.isNotEmpty()) {
        SummarySection(
            headerRes = if (isExport) R.string.settings_backup_summary_section_settings
                        else R.string.settings_backup_summary_section_settings_applied,
            items = settings,
        )
    }
    if (secrets.isNotEmpty()) {
        SummarySection(
            headerRes = if (isExport) R.string.settings_backup_summary_section_secrets
                        else R.string.settings_backup_summary_section_secrets_applied,
            items = secrets,
        )
    }
}

@Composable
private fun SummarySection(headerRes: Int, items: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(headerRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { res ->
            Text(
                "• " + stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}