package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Card listing available Whisper models with per-row status + actions.
 *
 * Layout per row:
 *   ┌──────────────────────────────────────────────────────┐
 *   │ Display name                              [Status pill]│
 *   │ {size} MB · {language}                                │
 *   │ ▓▓▓▓▓░░░░░ 47%                  (only while downloading)
 *   │ Error message in red             (only on failure)    │
 *   │ [Action button] [Action button]                       │
 *   └──────────────────────────────────────────────────────┘
 */
@Composable
fun WhisperModelsCard(
    vm: WhisperModelsViewModel,
    modifier: Modifier = Modifier,
) {
    val models by vm.models.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.whisper_models_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.whisper_models_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            models.forEachIndexed { i, state ->
                if (i > 0) HorizontalDivider()
                ModelRow(
                    state = state,
                    onDownload = { vm.download(state.info.id) },
                    onCancel = { vm.cancelDownload(state.info.id) },
                    onDelete = { vm.delete(state.info.id) },
                    onSetActive = { vm.setActive(state.info.id) },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    state: ModelUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.info.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            StatusPill(state)
        }
        Text(
            stringResource(
                R.string.whisper_model_size_format,
                approxSizeMb(state.info.approxSizeBytes),
                state.info.languageLabel,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val progress = state.progress
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.whisper_model_progress_format, (progress * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.errorMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ActionRow(state, onDownload, onCancel, onDelete, onSetActive)
    }
}

@Composable
private fun StatusPill(state: ModelUiState) {
    val (label, color) = when {
        state.isActive -> stringResource(R.string.whisper_model_status_active) to MaterialTheme.colorScheme.primary
        state.isDownloaded -> stringResource(R.string.whisper_model_status_downloaded) to MaterialTheme.colorScheme.secondary
        else -> return
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ActionRow(
    state: ModelUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.progress != null -> {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.whisper_model_action_cancel))
                }
            }
            state.isDownloaded -> {
                if (!state.isActive) {
                    OutlinedButton(onClick = onSetActive) {
                        Text(stringResource(R.string.whisper_model_action_set_active))
                    }
                }
                TextButton(
                    onClick = onDelete,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.whisper_model_action_delete))
                }
            }
            else -> {
                OutlinedButton(onClick = onDownload) {
                    Text(stringResource(R.string.whisper_model_action_download))
                }
            }
        }
    }
}

/** Round bytes to the nearest MB for display. */
private fun approxSizeMb(bytes: Long): Int = (bytes / 1_000_000L).toInt()
