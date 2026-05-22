package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Settings card for managing app aliases — `spoken phrase → app` overrides that
 * fix Whisper mishears ("adobe" → Audible) and nicknames ("podcasts" → Pocket
 * Casts). Lists existing aliases (deletable) and an add form: a phrase field +
 * a type-to-filter installed-app picker.
 */
@Composable
fun AppAliasesCard(
    vm: AppAliasesViewModel,
    modifier: Modifier = Modifier,
) {
    val aliases by vm.aliases.collectAsState()
    val apps by vm.installedApps.collectAsState()

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitleWithInfo(
                title = stringResource(R.string.app_aliases_header),
                description = stringResource(R.string.app_aliases_desc),
            )

            if (aliases.isEmpty()) {
                Text(
                    stringResource(R.string.app_aliases_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                aliases.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("“${row.phrase}”", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (row.installed) row.appLabel
                                else stringResource(R.string.app_aliases_not_installed, row.appLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.remove(row.phrase) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.app_aliases_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            AddAliasForm(
                apps = apps,
                onAdd = { phrase, pkg -> vm.add(phrase, pkg) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAliasForm(
    apps: List<com.lazydevs.wristotle.apps.InstalledApp>,
    onAdd: (phrase: String, packageId: String) -> Unit,
) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var selectedPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val filtered = remember(appQuery, apps) {
        if (appQuery.isBlank()) apps.take(MAX_SUGGESTIONS)
        else apps.filter { it.label.contains(appQuery, ignoreCase = true) }.take(MAX_SUGGESTIONS)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text(stringResource(R.string.app_aliases_phrase_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = appQuery,
                onValueChange = {
                    appQuery = it
                    selectedPkg = null // typing again invalidates the prior pick
                    expanded = true
                },
                label = { Text(stringResource(R.string.app_aliases_app_label)) },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filtered.forEach { app ->
                    DropdownMenuItem(
                        text = { Text(app.label) },
                        onClick = {
                            appQuery = app.label
                            selectedPkg = app.packageId
                            expanded = false
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                onAdd(phrase.trim(), selectedPkg!!)
                phrase = ""
                appQuery = ""
                selectedPkg = null
            },
            enabled = phrase.isNotBlank() && selectedPkg != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.app_aliases_add))
        }
    }
}

private const val MAX_SUGGESTIONS = 12
