// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lazydevs.wristotle.ui.components.AliasRowsDialog
import com.lazydevs.wristotle.ui.components.SecondaryLine

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
    var showDialog by remember { mutableStateOf(false) }

    // Re-read on (re)entry so an alias added from the Conversation tab's
    // quick-add popup shows up here — the prefs-backed store isn't observable,
    // so the VM snapshot would otherwise stay stale until the next onResume.
    LaunchedEffect(Unit) { vm.refresh() }

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
                OutlinedButton(
                    onClick = { showDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.app_aliases_show, aliases.size))
                }
            }

            AddAliasForm(
                apps = apps,
                onAdd = { phrase, pkg -> vm.add(phrase, pkg) },
            )
        }
    }

    if (showDialog) {
        AppAliasesDialog(
            aliases = aliases,
            onDelete = { vm.remove(it) },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun AppAliasesDialog(
    aliases: List<AliasRow>,
    onDelete: (phrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val notInstalledFmt = stringResource(R.string.app_aliases_not_installed)
    AliasRowsDialog(
        title = stringResource(R.string.app_aliases_header),
        rows = aliases,
        primaryOf = { it.phrase },
        secondaryOf = { row ->
            if (row.installed) SecondaryLine(row.appLabel)
            else SecondaryLine(String.format(notInstalledFmt, row.appLabel), warn = true)
        },
        deleteContentDescription = stringResource(R.string.app_aliases_delete),
        doneLabel = stringResource(R.string.app_aliases_done),
        onDelete = { onDelete(it.phrase) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddAliasForm(
    apps: List<com.lazydevs.wristotle.apps.InstalledApp>,
    onAdd: (phrase: String, packageId: String) -> Unit,
    initialPhrase: String = "",
) {
    var phrase by rememberSaveable { mutableStateOf(initialPhrase) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var selectedPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    // Show every match (the dropdown scrolls), alphabetized so the full
    // app list is browsable when the field is empty — capping the list hid
    // most installed apps behind a query the user had to guess.
    val filtered = remember(appQuery, apps) {
        val matches = if (appQuery.isBlank()) apps
            else apps.filter { it.label.contains(appQuery, ignoreCase = true) }
        matches.sortedBy { it.label.lowercase() }
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
            // Eager items, not a LazyColumn: ExposedDropdownMenu sizes itself
            // via intrinsic measurement of its content, which a LazyColumn
            // (SubcomposeLayout) can't answer — it crashes. The menu scrolls
            // on its own, and the launcher-app list is small enough that
            // composing the rows up front is fine.
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