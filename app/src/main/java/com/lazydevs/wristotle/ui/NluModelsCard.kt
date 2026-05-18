package com.lazydevs.wristotle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
 * Card listing available NLU sentence-encoder models. Mirrors the layout
 * conventions of [WhisperModelsCard] — one row per model, tap-to-activate
 * for downloaded inactive rows, single contextual icon button on the right
 * (Download / Delete / Cancel), inline progress bar while downloading.
 *
 * Phase 1: the only download here is MiniLM-L6-v2 INT8. The classifier
 * itself stays as the stub until Phase 2 wires `EmbeddingIntentClassifier`.
 */
@Composable
fun NluModelsCard(
    vm: NluModelsViewModel,
    modifier: Modifier = Modifier,
) {
    val models by vm.models.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitleWithInfo(
                title = stringResource(R.string.nlu_models_header),
                description = stringResource(R.string.nlu_models_desc),
            )
            models.forEachIndexed { i, state ->
                if (i > 0) HorizontalDivider()
                NluModelRow(
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
private fun NluModelRow(
    state: ModelUiState<com.lazydevs.wristotle.speech.nlu.model.NluModelInfo>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    val rowModifier = if (state.isDownloaded && !state.isActive && state.progress == null) {
        Modifier.fillMaxWidth().clickable(onClick = onSetActive).padding(vertical = 2.dp)
    } else {
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
    }
    Column(modifier = rowModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Left column: stack name above metadata so long display names
            // don't squeeze the secondary text into a 1-char-wide vertical.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.info.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.whisper_model_size_format,
                        approxSizeMb(state.info.approxSizeBytes),
                        state.info.architectureLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isActive) ActiveModelPill()
            RowActions(state, onDownload, onCancel, onDelete)
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
private fun RowActions(
    state: ModelUiState<com.lazydevs.wristotle.speech.nlu.model.NluModelInfo>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
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
