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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.main_screen_title)) }) }
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
                    Text(stringResource(R.string.status_card_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (allGranted) stringResource(R.string.status_active)
                        else stringResource(R.string.status_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (allGranted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(stringResource(R.string.permissions_header), style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.primary)

            PermissionRow(
                stringResource(R.string.perm_contacts_label),
                stringResource(R.string.perm_contacts_desc),
                perms.contacts
            )
            PermissionRow(
                stringResource(R.string.perm_calls_label),
                stringResource(R.string.perm_calls_desc),
                perms.callPhone
            )
            PermissionRow(
                stringResource(R.string.perm_sms_label),
                stringResource(R.string.perm_sms_desc),
                perms.sendSms
            )

            if (!allGranted) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.grant_permissions_button))
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                stringResource(R.string.usage_instructions),
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
            contentDescription = if (granted) stringResource(R.string.content_desc_granted)
                                 else stringResource(R.string.content_desc_denied),
            tint = if (granted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.error
        )
    }
}
