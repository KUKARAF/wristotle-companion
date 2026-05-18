package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Settings card surfacing the installed-app index state and the manual
 * rescan trigger. The user has to tap once to populate the index — and
 * again whenever they install or uninstall an app — for the
 * `open <app>` / `play <app>` voice commands to know about it.
 */
@Composable
fun AppIndexCard(vm: AppIndexViewModel) {
    val state by vm.state.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.app_index_header),
                description = stringResource(R.string.app_index_desc),
            )

            val statusText = when {
                state.count == 0 && state.lastScannedAtMs == null ->
                    stringResource(R.string.app_index_status_empty)
                else -> stringResource(
                    R.string.app_index_status_format,
                    state.count,
                    state.lastScannedAtMs?.let { relativeAgo(it) }
                        ?: stringResource(R.string.app_index_status_never),
                )
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.count == 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
            )

            Button(
                onClick = { vm.rescan() },
                enabled = !state.isScanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        if (state.isScanning) stringResource(R.string.app_index_scanning)
                        else stringResource(R.string.app_index_scan_button)
                    )
                }
            }
        }
    }
}

private fun relativeAgo(epochMs: Long): String {
    val deltaSec = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0)
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86400}d ago"
    }
}
