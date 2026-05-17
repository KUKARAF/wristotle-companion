package com.lazydevs.wristotle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Compact card listing available Whisper models. One row per model:
 *
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │ tiny.en  ·  75 MB English   [Active]                     [🗑] │
 *   │ ▓▓▓▓▓░░░░  47%               (only while downloading)         │
 *   │ Error message in red          (only on failure)               │
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Interaction: tap an unactive downloaded row to make it the active
 * model. Right-side icon button is single-purpose by state — Download
 * for not-yet-downloaded, Delete for downloaded, Cancel for in-flight
 * download. The progress bar and error text only render when relevant.
 */
@Composable
fun WhisperModelsCard(
    vm: WhisperModelsViewModel,
    modifier: Modifier = Modifier,
) {
    val models by vm.models.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.whisper_models_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.whisper_models_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
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
    // Whole row is clickable when this is a downloaded but inactive model —
    // tap-to-activate is more discoverable than a small icon button.
    val rowModifier = if (state.isDownloaded && !state.isActive && state.progress == null) {
        Modifier.fillMaxWidth().clickable(onClick = onSetActive).padding(vertical = 2.dp)
    } else {
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
    }
    Column(modifier = rowModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.info.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "  · " + stringResource(
                    R.string.whisper_model_size_format,
                    approxSizeMb(state.info.approxSizeBytes),
                    state.info.languageLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.isActive) ActivePill()
            RowActions(state, onDownload, onCancel, onDelete, onSetActive)
        }
        val progress = state.progress
        if (progress != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.whisper_model_progress_format, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.errorMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ActivePill() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(
                stringResource(R.string.whisper_model_status_active),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RowActions(
    state: ModelUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSetActive: () -> Unit,  // wired via row click, kept for symmetry
) {
    when {
        state.progress != null -> {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = stringResource(R.string.whisper_model_action_cancel),
                )
            }
        }
        state.isDownloaded -> {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.whisper_model_action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        else -> {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.whisper_model_action_download),
                )
            }
        }
    }
}

/** Round bytes to the nearest MB for display. */
private fun approxSizeMb(bytes: Long): Int = (bytes / 1_000_000L).toInt()
