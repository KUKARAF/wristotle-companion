package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Root screen showing service status and per-permission grant state.
 *
 * @param vm                  Provides the current [PermissionState] via a [StateFlow].
 * @param onRequestPermissions Triggered when the user taps the "Grant Permissions" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
) {
    val perms by vm.permissions.collectAsState()
    val allGranted = perms.contacts && perms.callPhone && perms.sendSms

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wristotle") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status card — green when all permissions are granted, red otherwise.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Watch Listener", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (allGranted) "Active — ready to handle watch commands"
                        else "Waiting for permissions",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allGranted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Text("Permissions", style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.primary)

            PermissionRow("Read Contacts",  "Required to look up contacts by name", perms.contacts)
            PermissionRow("Make Calls",     "Required to dial from the watch",       perms.callPhone)
            PermissionRow("Send SMS",       "Required to text from the watch",       perms.sendSms)

            if (!allGranted) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permissions")
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Say \"call [name]\" or \"text [name] [message]\" on your Pebble.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A single row showing a permission's label, description, and grant status icon.
 *
 * @param label       Short name shown in the primary text slot.
 * @param description One-line explanation shown in the secondary text slot.
 * @param granted     Whether the permission is currently granted.
 */
@Composable
private fun PermissionRow(label: String, description: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (granted) "Granted" else "Denied",
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error
        )
    }
}
