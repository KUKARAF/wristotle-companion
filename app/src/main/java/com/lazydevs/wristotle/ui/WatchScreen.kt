package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Watch Bridge tab — service status plus the runtime permissions the
 * call/SMS handlers need (Contacts, Phone, SMS). The grant button only
 * requests *these* permissions; voice-input perms live on their own tab.
 */
@Composable
fun WatchScreen(
    vm: MainViewModel,
    onRequestWatchPermissions: () -> Unit,
) {
    val perms by vm.permissions.collectAsState()
    val allGranted = perms.contacts && perms.callPhone && perms.sendSms

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.status_card_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (allGranted) stringResource(R.string.status_active)
                    else stringResource(R.string.status_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (allGranted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    stringResource(R.string.permissions_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PermissionRow(
                    stringResource(R.string.perm_contacts_label),
                    stringResource(R.string.perm_contacts_desc),
                    perms.contacts,
                )
                PermissionRow(
                    stringResource(R.string.perm_calls_label),
                    stringResource(R.string.perm_calls_desc),
                    perms.callPhone,
                )
                PermissionRow(
                    stringResource(R.string.perm_sms_label),
                    stringResource(R.string.perm_sms_desc),
                    perms.sendSms,
                )
                if (!allGranted) {
                    Button(
                        onClick = onRequestWatchPermissions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.grant_permissions_button))
                    }
                }
            }
        }

        Text(
            stringResource(R.string.usage_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
