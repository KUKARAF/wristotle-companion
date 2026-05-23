package com.lazydevs.wristotle.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Settings card for the backup / restore feature.
 *
 *   - **Export…** opens the system Save-As dialog (`CreateDocument`) so the
 *     user can drop the ZIP in Drive / OneDrive / Files / wherever. Phase A
 *     ships text data only — Phase B layers on audio + optional password.
 *   - **Restore…** is shown but disabled in Phase A; it lights up in Phase C
 *     when the importer lands.
 */
@Composable
fun BackupCard(vm: BackupViewModel) {
    val isExporting by vm.isExporting.collectAsState()
    val context = LocalContext.current

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.export(uri)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val message = when (event) {
                is BackupEvent.ExportDone -> {
                    val kb = (event.result.bytes / 1024L).coerceAtLeast(1L)
                    context.getString(
                        R.string.settings_backup_export_success,
                        event.result.notes,
                        event.result.conversations,
                        event.result.reminders,
                        event.result.aliases,
                        kb,
                    )
                }
                is BackupEvent.ExportFailed ->
                    context.getString(R.string.settings_backup_export_failed, event.message)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val suggestedName = remember { vm.suggestedFilename() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_backup_header),
                description = stringResource(R.string.settings_backup_desc),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { createDocument.launch(suggestedName) },
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        if (isExporting) stringResource(R.string.settings_backup_exporting)
                        else stringResource(R.string.settings_backup_export_button)
                    )
                }
                // Restore is disabled in Phase A — lights up in Phase C.
                OutlinedButton(
                    onClick = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_backup_restore_coming_soon),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_restore_button))
                }
            }
        }
    }
}
