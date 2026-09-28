// SPDX-License-Identifier: AGPL-3.0-only

package com.lazydevs.wristotle.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.notesserver.NotesServerLoginActivity
import kotlinx.coroutines.launch

/**
 * Sign in to notes.osmosis.page. While signed in, notes and tasks sync
 * both ways (see NotesServerSync); signed out, they stay local-only.
 */
@Composable
fun NotesServerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as WristotleApplication
    val account by app.notesServerAuth.account.collectAsState()
    val status by app.notesServerSync.status.collectAsState()
    val scope = rememberCoroutineScope()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitleWithInfo(
                title = stringResource(R.string.notes_server_header),
                description = stringResource(R.string.notes_server_desc),
            )
            val signedIn = account
            if (signedIn == null) {
                Text(stringResource(R.string.notes_server_signed_out), style = MaterialTheme.typography.bodyMedium)
                status.lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { NotesServerLoginActivity.start(context) }) {
                        Text(stringResource(R.string.notes_server_login))
                    }
                    TextButton(onClick = { NotesServerLoginActivity.startInBrowser(context) }) {
                        Text(stringResource(R.string.notes_server_login_browser))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.notes_server_signed_in, signedIn.label.ifBlank { "?" }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val line = when {
                    status.syncing -> stringResource(R.string.notes_server_syncing)
                    status.lastError != null -> stringResource(R.string.notes_server_error, status.lastError!!)
                    status.lastSuccessMs > 0 -> stringResource(
                        R.string.notes_server_last_sync,
                        DateUtils.getRelativeTimeSpanString(status.lastSuccessMs).toString(),
                    )
                    else -> ""
                }
                if (line.isNotEmpty()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.lastError != null && !status.syncing) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.notesServerSync.requestSync() }, enabled = !status.syncing) {
                        Text(stringResource(R.string.notes_server_sync_now))
                    }
                    OutlinedButton(onClick = { scope.launch { app.notesServerSync.logout() } }) {
                        Text(stringResource(R.string.notes_server_logout))
                    }
                }
            }
        }
    }
}
