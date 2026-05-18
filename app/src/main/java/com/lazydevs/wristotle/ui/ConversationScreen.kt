package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import android.media.MediaPlayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.history.ConversationEntry
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Reverse-chronological list of every persisted interaction. Each card shows
 * the user query, the response that was sent back to the watch, a handler
 * badge, a success/failure indicator, and the wall-clock time.
 *
 * Clearing history lives on the Settings tab; this screen is read-only.
 */
@Composable
fun ConversationScreen(vm: ConversationViewModel) {
    val entries by vm.entries.collectAsState()

    // Single MediaPlayer instance shared across rows — starting playback on
    // one row stops playback on whichever row was previously playing. Held
    // as a nullable ref so we can fully reset between sources (MediaPlayer
    // doesn't let you setDataSource twice without resetting first).
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }

    fun stopPlayback() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        playingId = null
    }

    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            stringResource(R.string.conversation_header),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    // File may have been pruned by the 5-file cap even though
                    // the row still references it — render the button only if
                    // the file still exists on disk.
                    val audioFile = entry.audioFilePath?.let(::File)?.takeIf { it.exists() }
                    val isPlaying = playingId == entry.id
                    EntryCard(
                        entry = entry,
                        canPlayAudio = audioFile != null,
                        isPlaying = isPlaying,
                        onTogglePlayback = onToggle@{
                            if (isPlaying) {
                                stopPlayback()
                                return@onToggle
                            }
                            stopPlayback()
                            val file = audioFile ?: return@onToggle
                            player = MediaPlayer().apply {
                                runCatching {
                                    setDataSource(file.absolutePath)
                                    setOnCompletionListener { stopPlayback() }
                                    setOnErrorListener { _, _, _ ->
                                        stopPlayback(); true
                                    }
                                    prepare()
                                    start()
                                }.onFailure {
                                    runCatching { release() }
                                    player = null
                                    playingId = null
                                }
                            }
                            if (player != null) playingId = entry.id
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.conversation_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.conversation_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(
    entry: ConversationEntry,
    canPlayAudio: Boolean,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Both bubbles stay in the surface-container family — same neutral
            // hue, different elevations — so the cards feel cohesive instead of
            // bright/competing. User on the higher elevation so the eye lands
            // on the query first; response sits lower as background context.
            MessageBubble(
                text = entry.userQuery,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            MessageBubble(
                text = entry.responseText,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // Footer row: handler / location / success / timestamp
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(entry.handler) },
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(
                            if (entry.requiresCompanion) R.string.conversation_location_phone
                            else R.string.conversation_location_watch
                        ))
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (entry.requiresCompanion) Icons.Default.PhoneAndroid
                                          else Icons.Default.Watch,
                            contentDescription = null,
                            modifier = Modifier.height(16.dp),
                        )
                    },
                )
                Icon(
                    imageVector = if (entry.success) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = if (entry.success) {
                        stringResource(R.string.content_desc_granted)
                    } else {
                        stringResource(R.string.content_desc_denied)
                    },
                    tint = if (entry.success) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (canPlayAudio) {
                    IconButton(
                        onClick = onTogglePlayback,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.conversation_stop_audio
                                else R.string.conversation_play_audio
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Push timestamp to the far right when it shares a row with the chips,
                // or let it wrap onto its own line otherwise.
                Text(
                    text = formatTimestamp(entry.timestampEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            // Optional perf details — only when we have them
            val perfBits = buildList {
                entry.inferenceDurationMs?.let { add("inf ${it} ms") }
                entry.audioDurationMs?.let { add("aud ${it} ms") }
                entry.audioCtx?.let { add("ctx $it") }
                entry.confidence?.let { add("conf %.2f".format(it)) }
            }
            if (perfBits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    perfBits.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Shadow-mode NLU prediction. Phase 2 only logs it; Phase 3 will
            // actually route on it. Surfaced so users (and us) can see what
            // the classifier would have picked before flipping the switch.
            val nluIntent = entry.nluIntent
            val nluConfidence = entry.nluConfidence
            if (nluIntent != null && nluConfidence != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "predicted: %s (%.2f)".format(nluIntent, nluConfidence),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Wrap in a SelectionContainer so users can long-press and copy the
        // text. Scoped per-bubble so a selection drag can't cross from the
        // user query into the system response and pick up nav-chip text.
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Locale-aware short date+time. Could swap for prettytime later for "5 min ago"
 * style — keeping this simple for v1 so the screen is testable without extra deps.
 */
private fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
