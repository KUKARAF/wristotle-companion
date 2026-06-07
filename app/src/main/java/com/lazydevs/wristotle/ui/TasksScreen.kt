// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lazydevs.wristotle.ui.nav.Screen
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.tasks.TaskEntity
import com.lazydevs.wristotle.ui.components.ConfirmDialog

/**
 * The Tasks tab. Three sections, top-to-bottom:
 *
 *  1. **Inline composer** — typed task creation. Tapping Add saves the
 *     trimmed body via the ViewModel and clears the field. Dictation
 *     is intentionally NOT exposed here for v1: the watch is the
 *     dictation surface, the companion tab is for typing + review.
 *  2. **Pending list** — checkbox-tap completes a task (moves it to
 *     the Completed section below); trailing delete icon removes it.
 *     Empty-state shows a hint when the list is empty.
 *  3. **Completed section** — collapsible header with the count. When
 *     expanded, each completed row shows strikethrough text + a
 *     "re-open" tap target on the checkbox (moves back to Pending)
 *     and a trailing delete icon. Defaults collapsed so the screen
 *     leads with what's actionable.
 *
 * A "Clear all" affordance lives at the top-right of the screen; tap
 * shows a confirmation dialog.
 */
@Composable
fun TasksScreen(vm: TasksViewModel) {
    val pending by vm.pending.collectAsState()
    val completed by vm.completed.collectAsState()

    var draft by rememberSaveable { mutableStateOf("") }
    var completedExpanded by rememberSaveable { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Header row — title on the left, "Clear all" trailing affordance
        // (only enabled when there's something to clear).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.tasks_header),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (pending.isNotEmpty() || completed.isNotEmpty()) {
                TextButton(onClick = { showClearAllDialog = true }) {
                    Text(stringResource(R.string.tasks_clear_all))
                }
            }
        }

        // Inline composer.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.tasks_add_placeholder)) },
                singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        vm.add(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.tasks_add_button),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // Pending list + collapsible completed section in one LazyColumn
        // so the user can scroll both as a unit when the list is long.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (pending.isEmpty()) {
                item { PendingEmptyState() }
            } else {
                items(pending, key = { "p-${it.id}" }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { vm.complete(task.id) },
                        onDelete = { vm.delete(task.id) },
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item {
                    CompletedSectionHeader(
                        count = completed.size,
                        expanded = completedExpanded,
                        onToggle = { completedExpanded = !completedExpanded },
                    )
                }
                if (completedExpanded) {
                    items(completed, key = { "c-${it.id}" }) { task ->
                        TaskRow(
                            task = task,
                            onToggle = { vm.reopen(task.id) },
                            onDelete = { vm.delete(task.id) },
                        )
                    }
                }
            }
        }
    }

    if (showClearAllDialog) {
        ConfirmDialog(
            title = stringResource(R.string.tasks_clear_all_title),
            message = stringResource(R.string.tasks_clear_all_message),
            confirmLabel = stringResource(R.string.tasks_clear_all_apply),
            onConfirm = {
                vm.deleteAll()
                showClearAllDialog = false
            },
            onDismiss = { showClearAllDialog = false },
        )
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The checkbox + text behave as one tap target so the row
            // feels natural to complete on. Trailing delete is a
            // separate target so an accidental check doesn't delete.
            val toggleDesc = if (task.completed)
                stringResource(R.string.tasks_reopen_one)
            else
                stringResource(R.string.tasks_complete_one)
            IconButton(onClick = onToggle) {
                if (task.completed) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = toggleDesc,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Circle,
                        contentDescription = toggleDesc,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = task.text,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.completed)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.tasks_delete_one),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompletedSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tasks_completed_section, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PendingEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = Screen.Tasks.tint.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(32.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Screen.Tasks.tint,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.tasks_empty_pending_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.tasks_empty_pending_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}