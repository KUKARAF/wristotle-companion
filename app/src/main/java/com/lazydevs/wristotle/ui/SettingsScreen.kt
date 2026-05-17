package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import kotlinx.coroutines.launch

/**
 * Settings tab — holds four cards:
 *   • Whisper model catalog / download / activation
 *   • NLU sentence-encoder model (optional; enables natural-language commands)
 *   • Intent learning (toggle + clear learned examples)
 *   • Conversation-history maintenance (retention picker + Clear all)
 *
 * Add more setting sections as new cards here as features grow.
 */
@Composable
fun SettingsScreen(
    modelsVm: WhisperModelsViewModel,
    nluModelsVm: NluModelsViewModel,
    nluSettingsVm: NluSettingsViewModel,
    conversationVm: ConversationViewModel,
) {
    val retentionDays by conversationVm.retentionDays.collectAsState()
    val learningEnabled by nluSettingsVm.learningEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    // State for the "you're about to shrink the window and lose N entries" confirm dialog.
    var pendingShrink by remember { mutableStateOf<PendingShrink?>(null) }
    var showClearLearnedConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhisperModelsCard(vm = modelsVm)
        NluModelsCard(vm = nluModelsVm)
        IntentLearningCard(
            learningEnabled = learningEnabled,
            onToggle = nluSettingsVm::setLearningEnabled,
            onClearLearned = { showClearLearnedConfirm = true },
        )
        HistoryRetentionCard(
            selectedDays = retentionDays,
            options = conversationVm.retentionOptions,
            onSelect = { newDays ->
                if (newDays >= retentionDays) {
                    // Growing the window can't delete anything — apply silently.
                    conversationVm.setRetentionDays(newDays)
                } else {
                    // Shrinking always shows a confirm dialog — even if zero
                    // entries would be lost today, the user is committing to
                    // tighter pruning for future ones, which is worth a
                    // deliberate Yes.
                    scope.launch {
                        val count = conversationVm.countOlderThan(newDays)
                        pendingShrink = PendingShrink(newDays, count)
                    }
                }
            },
            onClear = conversationVm::clearAll,
        )
    }

    pendingShrink?.let { shrink ->
        ShrinkConfirmDialog(
            newDays = shrink.newDays,
            entriesToDelete = shrink.entriesToDelete,
            onConfirm = {
                conversationVm.setRetentionDays(shrink.newDays)
                pendingShrink = null
            },
            onDismiss = { pendingShrink = null },
        )
    }

    if (showClearLearnedConfirm) {
        AlertDialog(
            onDismissRequest = { showClearLearnedConfirm = false },
            title = { Text(stringResource(R.string.settings_learning_clear_title)) },
            text = { Text(stringResource(R.string.settings_learning_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    nluSettingsVm.clearLearned()
                    showClearLearnedConfirm = false
                }) { Text(stringResource(R.string.settings_learning_clear_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearLearnedConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun IntentLearningCard(
    learningEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClearLearned: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_learning_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_learning_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_learning_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = learningEnabled, onCheckedChange = onToggle)
            }
            Button(
                onClick = onClearLearned,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_learning_clear_button))
            }
        }
    }
}

private data class PendingShrink(val newDays: Int, val entriesToDelete: Int)

@Composable
private fun ShrinkConfirmDialog(
    newDays: Int,
    entriesToDelete: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_history_shrink_title)) },
        text = {
            val message = if (entriesToDelete == 0) {
                // No data loss today; the dialog confirms the intent to
                // tighten future pruning.
                stringResource(R.string.settings_history_shrink_message_none, newDays)
            } else {
                pluralStringResource(
                    R.plurals.settings_history_shrink_message,
                    entriesToDelete,
                    entriesToDelete,
                    newDays,
                )
            }
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_history_shrink_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRetentionCard(
    selectedDays: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_history_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_history_retention_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = pluralStringResource(R.plurals.settings_history_retention_days, selectedDays, selectedDays),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_history_retention_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { days ->
                        DropdownMenuItem(
                            text = { Text(pluralStringResource(R.plurals.settings_history_retention_days, days, days)) },
                            onClick = {
                                onSelect(days)
                                expanded = false
                            },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.settings_history_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { showConfirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.conversation_clear_all))
            }
        }
    }

    if (showConfirm) {
        ClearConfirmDialog(
            onConfirm = {
                onClear()
                showConfirm = false
            },
            onDismiss = { showConfirm = false },
        )
    }
}

@Composable
private fun ClearConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversation_clear_confirm_title)) },
        text = { Text(stringResource(R.string.conversation_clear_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.conversation_clear_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
