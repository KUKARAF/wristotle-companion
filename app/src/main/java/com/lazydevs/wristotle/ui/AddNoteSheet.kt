package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Bottom sheet for creating a new note on the companion.
 *
 * Two input paths:
 *   - Type into the text field directly (always available).
 *   - Tap the mic to dictate via Wristotle's Whisper recognizer + the
 *     phone's microphone. Partial transcripts stream live into the
 *     field; the final replaces the field content.
 *
 * Audio is attached automatically when the conversation-audio capture
 * toggle is on — the recognizer publishes the `.wav` path via the
 * same `lastCapturedAudioPath` channel the listener uses for watch
 * dictation. With capture off, the note saves as text-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNoteSheet(vm: NotesViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft by vm.draftBody.collectAsState()
    val dictationState by vm.dictationState.collectAsState()
    val dictationError by vm.dictationError.collectAsState()
    val isRecording = dictationState == NotesViewModel.DictationState.RECORDING
    val isTranscribing = dictationState == NotesViewModel.DictationState.TRANSCRIBING

    // Auto-clear the transient error after a moment so it doesn't linger.
    LaunchedEffect(dictationError) {
        if (dictationError != null) {
            kotlinx.coroutines.delay(3_000)
            vm.clearDictationError()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { vm.cancelDraft(); onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.notes_add_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = vm::setDraftBody,
                label = { Text(stringResource(R.string.notes_add_field_label)) },
                placeholder = { Text(stringResource(R.string.notes_add_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                // Disable typing while the mic is active to avoid concurrent edits.
                enabled = !isRecording && !isTranscribing,
            )
            dictationError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    enabled = !isTranscribing,
                    onClick = { if (isRecording) vm.stopDictation() else vm.startDictation() },
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (isRecording) R.string.notes_add_dictate_stop
                            else R.string.notes_add_dictate_start,
                        ),
                        tint = if (isRecording) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.cancelDraft(); onDismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = { vm.saveDraft(); onDismiss() },
                        enabled = draft.isNotBlank() && !isRecording && !isTranscribing,
                    ) {
                        Text(stringResource(R.string.notes_add_save))
                    }
                }
            }
        }
    }
}
