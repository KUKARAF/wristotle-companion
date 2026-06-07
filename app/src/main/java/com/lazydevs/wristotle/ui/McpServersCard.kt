// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.lazydevs.wristotle.ui.components.PasswordField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.mcp.McpServerEntity
import com.lazydevs.wristotle.mcp.McpTool
import com.lazydevs.wristotle.mcp.ToolCallResult
import com.lazydevs.wristotle.mcp.looksLikeFullAuthHeader

/**
 * One Card for the add-server form, plus one Card per configured
 * server (single-line summary; tools open in a popup so the page
 * stays scannable when the user has many servers and a 40+ tool list
 * doesn't overflow). The tools dialog stays mounted while the args +
 * result dialogs stack on top so users can fire multiple tool calls
 * without re-opening the list.
 */
@Composable
fun McpServersCard(vm: McpServersViewModel) {
    val servers by vm.servers.collectAsState()
    val probeStates by vm.probeStates.collectAsState()
    val callState by vm.callState.collectAsState()

    var openServer by remember { mutableStateOf<McpServerEntity?>(null) }
    var editingServer by remember { mutableStateOf<McpServerEntity?>(null) }
    var pendingTool by remember { mutableStateOf<Pair<McpServerEntity, McpTool>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardTitleWithInfo(
                    title = stringResource(R.string.settings_mcp_header),
                    description = stringResource(R.string.settings_mcp_desc),
                )
                AddMcpServerForm(onAdd = vm::addServer)
                if (servers.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_mcp_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        servers.forEach { server ->
            McpServerCard(
                server = server,
                probeState = probeStates[server.id] ?: ServerProbeState.Idle,
                onOpen = {
                    openServer = server
                    // If never loaded yet, kick off a fetch so the dialog
                    // doesn't open empty waiting for the user to tap refresh.
                    if (probeStates[server.id] == null ||
                        probeStates[server.id] == ServerProbeState.Idle
                    ) {
                        vm.refreshTools(server)
                    }
                },
                onRefresh = {
                    openServer = server
                    vm.refreshTools(server)
                },
                onEdit = { editingServer = server },
                onDelete = { vm.deleteServer(server.id) },
                onToggleEnabled = { vm.setEnabled(server, it) },
            )
        }
    }

    editingServer?.let { server ->
        EditMcpServerDialog(
            server = server,
            onSave = { name, url, streamable, authHeader ->
                vm.editServer(server, name, url, streamable, authHeader)
            },
            onDismiss = { editingServer = null },
        )
    }

    openServer?.let { server ->
        ToolsListDialog(
            server = server,
            probeState = probeStates[server.id] ?: ServerProbeState.Idle,
            onRefresh = { vm.refreshTools(server) },
            onToolClicked = { tool -> pendingTool = server to tool },
            onDismiss = { openServer = null },
        )
    }

    pendingTool?.let { (server, tool) ->
        CallToolDialog(
            tool = tool,
            onDismiss = { pendingTool = null },
            onCall = { args -> vm.callTool(server, tool.name, args) },
        )
    }

    when (val s = callState) {
        is ToolCallState.Calling -> ToolCallInFlightDialog(toolName = s.toolName)
        is ToolCallState.Done -> ToolCallResultDialog(
            result = s.result,
            onDismiss = { vm.dismissCallResult() },
        )
        ToolCallState.Idle -> { /* no dialog */ }
    }
}

@Composable
private fun AddMcpServerForm(
    onAdd: (name: String, url: String, streamable: Boolean, authHeader: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var streamable by remember { mutableStateOf(true) }
    var authHeader by remember { mutableStateOf("") }
    var showSchemeWarning by remember { mutableStateOf(false) }

    val commit = {
        onAdd(name, url, streamable, authHeader.takeIf { it.isNotBlank() })
        name = ""
        url = ""
        authHeader = ""
        streamable = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.settings_mcp_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_mcp_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            value = authHeader,
            onChange = { authHeader = it },
            label = stringResource(R.string.settings_mcp_auth_label),
            placeholder = stringResource(R.string.settings_mcp_auth_placeholder),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.settings_mcp_streamable_label),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = streamable, onCheckedChange = { streamable = it })
        }
        Button(
            onClick = {
                if (looksLikeFullAuthHeader(authHeader)) commit() else showSchemeWarning = true
            },
            enabled = name.isNotBlank() && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_mcp_add_button))
        }
    }

    if (showSchemeWarning) {
        AuthHeaderSchemeWarningDialog(
            onConfirm = { showSchemeWarning = false; commit() },
            onDismiss = { showSchemeWarning = false },
        )
    }
}

/** Compact server summary row. Tap = open tools dialog. */
@Composable
private fun McpServerCard(
    server: McpServerEntity,
    probeState: ServerProbeState,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Disabled rows dim the descriptive text but the Switch + icon
            // strip stay full-opacity so re-enabling and deleting remain
            // visually obvious.
            val textAlpha = if (server.enabled) 1f else 0.38f
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .alpha(textAlpha),
            ) {
                Text(server.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    server.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ServerStatusLine(server, probeState)
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = onToggleEnabled,
            )
            RefreshIconOrSpinner(
                isLoading = probeState is ServerProbeState.Loading,
                onClick = onRefresh,
                enabled = server.enabled,
            )
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.settings_mcp_edit),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.settings_mcp_delete),
                )
            }
        }
    }
}

/**
 * Edit-existing-server dialog. Mirrors [AddMcpServerForm]'s fields
 * (pre-filled from the entity) inside an AlertDialog. The ViewModel's
 * [McpServersViewModel.editServer] evicts the cached integration so
 * the next tools refresh picks up the new URL / auth without an app
 * restart.
 */
@Composable
private fun EditMcpServerDialog(
    server: McpServerEntity,
    onSave: (name: String, url: String, streamable: Boolean, authHeader: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.url) }
    var streamable by remember { mutableStateOf(server.streamable) }
    var authHeader by remember { mutableStateOf(server.authHeader.orEmpty()) }
    var showSchemeWarning by remember { mutableStateOf(false) }

    val commit = {
        onSave(name, url, streamable, authHeader.takeIf { it.isNotBlank() })
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_mcp_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_mcp_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_mcp_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = authHeader,
                    onChange = { authHeader = it },
                    label = stringResource(R.string.settings_mcp_auth_label),
                    placeholder = stringResource(R.string.settings_mcp_auth_placeholder),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.settings_mcp_streamable_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = streamable, onCheckedChange = { streamable = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (looksLikeFullAuthHeader(authHeader)) commit() else showSchemeWarning = true
                },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_mcp_save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_mcp_cancel_button))
            }
        },
    )

    if (showSchemeWarning) {
        AuthHeaderSchemeWarningDialog(
            onConfirm = { showSchemeWarning = false; commit() },
            onDismiss = { showSchemeWarning = false },
        )
    }
}

/**
 * Shared between Add + Edit flows. Fires when the user is about to save
 * an Authorization value that doesn't start with a known scheme prefix
 * (Bearer / Basic / Token / etc.) — the classic "I pasted a raw token"
 * mistake that surfaces later as a confusing 401 from the server.
 */
@Composable
private fun AuthHeaderSchemeWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_mcp_auth_no_scheme_title)) },
        text = { Text(stringResource(R.string.settings_mcp_auth_no_scheme_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_mcp_save_anyway_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_mcp_cancel_button))
            }
        },
    )
}

/** One-line status text under the URL: tool count / "not loaded" / error. */
@Composable
private fun ServerStatusLine(server: McpServerEntity, probeState: ServerProbeState) {
    val transport = if (server.streamable)
        stringResource(R.string.settings_mcp_transport_streamable_short)
    else
        stringResource(R.string.settings_mcp_transport_sse_short)
    val status: String = when (probeState) {
        ServerProbeState.Idle -> stringResource(R.string.settings_mcp_status_idle)
        ServerProbeState.Loading -> stringResource(R.string.settings_mcp_loading)
        is ServerProbeState.Failed -> stringResource(R.string.settings_mcp_status_failed)
        is ServerProbeState.Tools -> stringResource(
            R.string.settings_mcp_status_tools, probeState.tools.size,
        )
    }
    Text(
        "$transport • $status",
        style = MaterialTheme.typography.labelSmall,
        color = if (probeState is ServerProbeState.Failed)
            MaterialTheme.colorScheme.error
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToolsDialogBody(
    probeState: ServerProbeState,
    onToolClicked: (McpTool) -> Unit,
) {
    when (probeState) {
        ServerProbeState.Idle -> Text(
            stringResource(R.string.settings_mcp_tap_refresh),
            style = MaterialTheme.typography.bodySmall,
        )
        ServerProbeState.Loading -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp).padding(top = 12.dp),
                strokeWidth = 3.dp,
            )
        }
        is ServerProbeState.Failed -> Text(
            stringResource(R.string.settings_mcp_probe_failed, probeState.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is ServerProbeState.Tools -> {
            if (probeState.tools.isEmpty()) {
                Text(
                    stringResource(R.string.settings_mcp_no_tools),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(probeState.tools) { index, tool ->
                        if (index > 0) HorizontalDivider()
                        ListItem(
                            modifier = Modifier.clickable { onToolClicked(tool) },
                            headlineContent = {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                            },
                            supportingContent = tool.description
                                ?.takeIf { it.isNotBlank() }
                                ?.let { desc ->
                                    {
                                        Text(
                                            desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshIconOrSpinner(
    isLoading: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled && !isLoading) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.settings_mcp_refresh),
            )
        }
    }
}

/**
 * Popup listing one server's tools. Stays open while the user fires
 * tool calls (the args + result dialogs stack on top). Header has its
 * own refresh button so an out-of-date list can be re-fetched without
 * closing.
 */
@Composable
private fun ToolsListDialog(
    server: McpServerEntity,
    probeState: ServerProbeState,
    onRefresh: () -> Unit,
    onToolClicked: (McpTool) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, modifier = Modifier.weight(1f))
                RefreshIconOrSpinner(
                    isLoading = probeState is ServerProbeState.Loading,
                    onClick = onRefresh,
                )
            }
        },
        text = { ToolsDialogBody(probeState, onToolClicked) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_mcp_close_button))
            }
        },
    )
}

@Composable
private fun CallToolDialog(
    tool: McpTool,
    onDismiss: () -> Unit,
    onCall: (jsonArgs: String) -> Unit,
) {
    var args by remember { mutableStateOf("{}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tool.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tool.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                tool.inputSchema?.let { schema ->
                    Text(
                        stringResource(R.string.settings_mcp_schema_label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        schema.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                }
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.settings_mcp_args_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onCall(args)
                onDismiss()
            }) {
                Text(stringResource(R.string.settings_mcp_call_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_mcp_cancel_button))
            }
        },
    )
}

/**
 * Non-dismissible "Calling X…" dialog shown while a tool call is in
 * flight. Replaced by [ToolCallResultDialog] on completion. Without
 * this the user only sees the result dialog and has no feedback that
 * anything happened between the Call tap and the result — confusing on
 * slow servers (GitHub MCP calls run multi-second sometimes).
 */
@Composable
private fun ToolCallInFlightDialog(toolName: String) {
    AlertDialog(
        onDismissRequest = { /* swallow — request is in flight */ },
        title = { Text(stringResource(R.string.settings_mcp_calling_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.settings_mcp_calling_text, toolName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { /* no button — non-cancellable in phase A */ },
    )
}

@Composable
private fun ToolCallResultDialog(result: ToolCallResult, onDismiss: () -> Unit) {
    val isError = result is ToolCallResult.Failure
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isError) stringResource(R.string.settings_mcp_result_failed)
                else stringResource(R.string.settings_mcp_result_success),
            )
        },
        text = {
            val text = when (result) {
                is ToolCallResult.Success -> result.text
                is ToolCallResult.Failure -> result.message
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.settings_mcp_close_button)) }
        },
    )
}