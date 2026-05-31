package com.lazydevs.wristotle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.mcp.McpServerEntity
import com.lazydevs.wristotle.mcp.McpTool
import com.lazydevs.wristotle.mcp.ToolCallResult

/**
 * Settings card for the MCP client (phase A). Lists configured HTTP
 * MCP servers, lets the user add / remove them, and probes each
 * server's tool list on demand. A tap on a tool opens a JSON-input
 * dialog and calls it — phase A's debug surface for sanity-checking
 * that the wiring works end-to-end before the AskAgent voice intent
 * ships in phase B.
 *
 * The card mirrors the AppAliasesCard / ContactAliasesCard pattern —
 * inline add form on top, list below — so users moving around Settings
 * see the same shape for "configure a list of things."
 */
@Composable
fun McpServersCard(vm: McpServersViewModel) {
    val servers by vm.servers.collectAsState()
    val probeStates by vm.probeStates.collectAsState()
    val callState by vm.callState.collectAsState()

    var pendingTool by remember { mutableStateOf<Pair<McpServerEntity, McpTool>?>(null) }

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
            } else {
                servers.forEachIndexed { index, server ->
                    if (index > 0) HorizontalDivider()
                    McpServerRow(
                        server = server,
                        probeState = probeStates[server.name] ?: ServerProbeState.Idle,
                        onRefresh = { vm.refreshTools(server) },
                        onDelete = { vm.deleteServer(server.id) },
                        onToolClicked = { tool -> pendingTool = server to tool },
                    )
                }
            }
        }
    }

    pendingTool?.let { (server, tool) ->
        CallToolDialog(
            tool = tool,
            onDismiss = { pendingTool = null },
            onCall = { args -> vm.callTool(server, tool.name, args) },
        )
    }

    val finishedCall = callState
    if (finishedCall is ToolCallState.Done) {
        ToolCallResultDialog(
            result = finishedCall.result,
            onDismiss = { vm.dismissCallResult() },
        )
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
        OutlinedTextField(
            value = authHeader,
            onValueChange = { authHeader = it },
            label = { Text(stringResource(R.string.settings_mcp_auth_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
                onAdd(name, url, streamable, authHeader.takeIf { it.isNotBlank() })
                name = ""
                url = ""
                authHeader = ""
                streamable = true
            },
            enabled = name.isNotBlank() && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_mcp_add_button))
        }
    }
}

@Composable
private fun McpServerRow(
    server: McpServerEntity,
    probeState: ServerProbeState,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onToolClicked: (McpTool) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    server.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (server.streamable)
                        stringResource(R.string.settings_mcp_transport_streamable)
                    else
                        stringResource(R.string.settings_mcp_transport_sse),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.settings_mcp_refresh))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.settings_mcp_delete))
            }
        }

        when (probeState) {
            ServerProbeState.Idle -> { /* nothing */ }
            ServerProbeState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.settings_mcp_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
            is ServerProbeState.Failed -> {
                Text(
                    stringResource(R.string.settings_mcp_probe_failed, probeState.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is ServerProbeState.Tools -> {
                if (probeState.tools.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_mcp_no_tools),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.settings_mcp_tools_count, probeState.tools.size),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    probeState.tools.forEach { tool ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToolClicked(tool) }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                            tool.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Spacer(Modifier.height(4.dp))
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
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.settings_mcp_close_button)) }
        },
    )
}
