package com.lazydevs.wristotle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.whisper.ModelInfo
import com.lazydevs.wristotle.speech.whisper.ModelTier
import com.lazydevs.wristotle.transport.PebbleCompanionDetector

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
    val companion by vm.pebbleCompanion.collectAsState()
    val noticeDismissed by vm.noticeDismissed.collectAsState()
    val modelsRevealed by vm.modelsRevealedAnyway.collectAsState()
    // Three orthogonal states:
    //   - hideEverything: rePebble is in front AND user hasn't yet
    //     opted in to seeing the models. We render either the notice
    //     (when not dismissed) or just the "Show models anyway" link.
    //   - showNotice: the rePebble explainer banner is visible.
    //     Independent of `revealed` — dismissing it doesn't unhide rows.
    //   - showModels: the model rows are visible. True for microPebble
    //     and Unknown unconditionally, and for rePebble only after the
    //     user explicitly taps "Show models anyway".
    val showModels =
        companion.whisperAppliesToWatchDictation || modelsRevealed
    val showNotice =
        !companion.whisperAppliesToWatchDictation && !noticeDismissed && !modelsRevealed
    val showRevealButton =
        !companion.whisperAppliesToWatchDictation && !modelsRevealed

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitleWithInfo(
                title = stringResource(R.string.whisper_models_header),
                description = stringResource(R.string.whisper_models_desc),
            )
            if (showNotice) {
                RePebbleNotice(
                    installed = companion.installed,
                    onDismiss = vm::dismissNotice,
                )
            }
            if (showRevealButton) {
                TextButton(onClick = vm::revealModelsAnyway) {
                    Text(stringResource(R.string.whisper_models_show_anyway))
                }
            }
            if (showModels) {
                val recommended = models.filter { it.info.recommended }
                val advanced = models.filterNot { it.info.recommended }
                var showAll by remember { mutableStateOf(false) }

                // Default view: recommended models grouped by tier. The tier
                // blurb explains each choice, so the user picks Fast/Balanced/
                // Accurate without seeing the raw variant + quantization zoo.
                ModelTier.entries.forEach { tier ->
                    val inTier = recommended.filter { it.info.tier == tier }
                    if (inTier.isNotEmpty()) {
                        TierHeader(label = tier.label, blurb = tier.blurb)
                        inTier.forEach { state -> ModelRowFor(vm, state) }
                    }
                }

                // Everything else (full / multilingual / exploratory variants)
                // behind an expander — adding new models to try never grows the
                // default list.
                if (advanced.isNotEmpty()) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(
                            if (showAll) stringResource(R.string.whisper_models_show_fewer)
                            else stringResource(R.string.whisper_models_show_all, advanced.size),
                        )
                    }
                    if (showAll) {
                        Text(
                            stringResource(R.string.whisper_models_all_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        advanced.forEach { state -> ModelRowFor(vm, state) }
                    }
                }

                // Inverse affordance for the cloud-dictation case: once
                // the user has revealed the models they may decide they
                // didn't actually want them on screen. Only meaningful
                // under cloud-dictation — for microPebble/Unknown the
                // rows are the normal default and there's nothing to
                // "hide back to."
                if (!companion.whisperAppliesToWatchDictation && modelsRevealed) {
                    TextButton(onClick = vm::hideModelsAnyway) {
                        Text(stringResource(R.string.whisper_models_hide_anyway))
                    }
                }
            }
        }
    }
}

@Composable
private fun TierHeader(label: String, blurb: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(label, style = MaterialTheme.typography.titleSmall)
    Text(
        blurb,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ModelRowFor(vm: WhisperModelsViewModel, state: ModelUiState<ModelInfo>) {
    ModelRow(
        state = state,
        onDownload = { vm.download(state.info.id) },
        onCancel = { vm.cancelDownload(state.info.id) },
        onDelete = { vm.delete(state.info.id) },
        onSetActive = { vm.setActive(state.info.id) },
    )
}

@Composable
private fun RePebbleNotice(
    installed: PebbleCompanionDetector.InstalledPackages,
    onDismiss: () -> Unit,
) {
    Spacer(modifier = Modifier.height(4.dp))
    // tertiaryContainer is the Material-3 surface for info/callouts —
    // distinct from the parent card's `surface` background, paired with
    // `onTertiaryContainer` for high-contrast body text in both light
    // and dark themes. surfaceVariant earlier blended into surface in
    // dark mode and made the notice hard to spot.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.whisper_models_repebble_notice_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.whisper_models_repebble_notice_body),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!installed.micropebble) {
                Text(
                    stringResource(R.string.whisper_models_repebble_notice_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ),
                ) {
                    Text(stringResource(R.string.conversation_repebble_notice_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    state: ModelUiState<com.lazydevs.wristotle.speech.whisper.ModelInfo>,
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
            // Stack name above size+lang on the left so long display names
            // (e.g. "Base (Multilingual)") don't squeeze the secondary line
            // into a 1-char-wide vertical column.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.info.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.whisper_model_size_with_ram_format,
                        approxSizeMb(state.info.approxSizeBytes),
                        approxRamMb(state.info.approxSizeBytes),
                        state.info.languageLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isActive) ActiveModelPill()
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
private fun RowActions(
    state: ModelUiState<com.lazydevs.wristotle.speech.whisper.ModelInfo>,
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

