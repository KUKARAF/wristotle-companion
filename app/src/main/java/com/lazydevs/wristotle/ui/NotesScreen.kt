package com.lazydevs.wristotle.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.notes.Note
import com.lazydevs.wristotle.ui.components.ConfirmDialog
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Reverse-chronological list of captured notes. Each card shows the note
 * body, the creation timestamp, an inline play button when the dictation's
 * audio was kept, and a delete button.
 *
 * Add / edit lives in a future phase — Phase A only renders watch-dictated
 * captures.
 */
@Composable
fun NotesScreen(vm: NotesViewModel) {
    val notes by vm.notes.collectAsState()
    val query by vm.query.collectAsState()

    // Shared MediaPlayer. Each note can have multiple audio clips (SEPARATE
    // append-audio mode produces one per append) — we render one play button
    // per clip and track which clip is currently playing.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var playingClipIndex by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteAll by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }

    fun stopPlayback() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        playingId = null
        playingClipIndex = null
    }

    fun playClip(noteId: Long, idx: Int, file: File) {
        stopPlayback()
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
                prepare()
                start()
            }.onFailure {
                runCatching { release() }
                player = null
            }
        }
        if (player != null) {
            playingId = noteId
            playingClipIndex = idx
        }
    }

    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    // Box is right here, not Scaffold — MainScreen already wraps the whole
    // app in a Scaffold (TopAppBar + bottom NavigationBar). Nesting a
    // second Scaffold inside duplicates the inset handling and yields
    // a layout that's off (extra top padding, FAB clipped by the bottom
    // nav).
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.notes_header), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            label = { Text(stringResource(R.string.notes_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (notes.isEmpty()) {
            EmptyNotesState(searchActive = query.isNotBlank())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    // Resolve audio paths + existence on IO so the LazyColumn
                    // item composition doesn't block Main on filesystem stats.
                    // Re-fires only when the encoded path string changes.
                    val audioFiles by produceState(
                        initialValue = emptyList<File>(),
                        note.audioFilePath,
                    ) {
                        value = withContext(Dispatchers.IO) {
                            com.lazydevs.wristotle.notes.NoteAudioPaths
                                .parse(note.audioFilePath)
                                .mapNotNull { File(it).takeIf(File::exists) }
                        }
                    }
                    val playingClipForThis = if (playingId == note.id) playingClipIndex else null
                    NoteCard(
                        note = note,
                        audioFiles = audioFiles,
                        playingClipIndex = playingClipForThis,
                        onPlayClip = { idx -> playClip(note.id, idx, audioFiles[idx]) },
                        onStopClip = { stopPlayback() },
                        onDelete = {
                            if (playingId == note.id) stopPlayback()
                            vm.delete(note.id)
                        },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { pendingDeleteAll = true }) {
                        Text(stringResource(R.string.notes_delete_all))
                    }
                }
            }
        }
    }

    if (pendingDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.notes_delete_all),
            message = stringResource(R.string.notes_delete_all_warning),
            confirmLabel = stringResource(R.string.notes_delete_all_confirm),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = {
                stopPlayback()
                vm.deleteAll()
                pendingDeleteAll = false
            },
            onDismiss = { pendingDeleteAll = false },
        )
    }

    FloatingActionButton(
        onClick = { showAddSheet = true },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp),
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.notes_add_fab_desc),
        )
    }
    } // close Box

    if (showAddSheet) {
        AddNoteSheet(vm = vm, onDismiss = { showAddSheet = false })
    }
}

@Composable
private fun EmptyNotesState(searchActive: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(
                if (searchActive) R.string.notes_empty_search_title
                else R.string.notes_empty_title,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (!searchActive) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.notes_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    audioFiles: List<File>,
    playingClipIndex: Int?,
    onPlayClip: (Int) -> Unit,
    onStopClip: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(note.body, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTimestamp(note.createdAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AudioClipControls(
                        audioFiles = audioFiles,
                        playingClipIndex = playingClipIndex,
                        onPlayClip = onPlayClip,
                        onStopClip = onStopClip,
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.notes_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders play/stop controls for a note's audio clips:
 *   - 0 clips → nothing
 *   - 1 clip  → single icon-only play/stop button (the common case)
 *   - N clips → row of numbered chips, one per clip, each independently playable
 */
@Composable
private fun AudioClipControls(
    audioFiles: List<File>,
    playingClipIndex: Int?,
    onPlayClip: (Int) -> Unit,
    onStopClip: () -> Unit,
) {
    if (audioFiles.isEmpty()) return
    if (audioFiles.size == 1) {
        val isPlaying = playingClipIndex == 0
        IconButton(onClick = { if (isPlaying) onStopClip() else onPlayClip(0) }) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.notes_stop_audio else R.string.notes_play_audio,
                ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        audioFiles.forEachIndexed { idx, _ ->
            val isThis = playingClipIndex == idx
            AssistChip(
                onClick = { if (isThis) onStopClip() else onPlayClip(idx) },
                label = { Text("${idx + 1}") },
                leadingIcon = {
                    Icon(
                        imageVector = if (isThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (isThis) R.string.notes_stop_audio else R.string.notes_play_audio,
                        ),
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/** See ConversationScreen's twin — same rationale. */
private val SHORT_DATE_FORMAT: DateFormat by lazy {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
}

private fun formatTimestamp(epochMs: Long): String =
    SHORT_DATE_FORMAT.format(Date(epochMs))
