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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.ui.components.AliasRowsDialog
import com.lazydevs.wristotle.ui.components.SecondaryLine
import kotlinx.coroutines.launch

/**
 * Settings card for managing contact aliases — `spoken phrase → contact`
 * overrides that fix Whisper mishears ("next mom" → mom) and nicknames
 * ("partner" → a specific stored contact). Shape mirrors
 * [AppAliasesCard] deliberately so users find it familiar.
 */
@Composable
fun ContactAliasesCard(
    vm: ContactAliasesViewModel,
    modifier: Modifier = Modifier,
) {
    val aliases by vm.aliases.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    // Re-read on (re)entry so an alias added elsewhere shows up here.
    // Prefs-backed store isn't observable on its own.
    LaunchedEffect(Unit) { vm.refresh() }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitleWithInfo(
                title = stringResource(R.string.contact_aliases_header),
                description = stringResource(R.string.contact_aliases_desc),
            )

            if (aliases.isEmpty()) {
                Text(
                    stringResource(R.string.contact_aliases_empty),
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
                    Text(stringResource(R.string.contact_aliases_show, aliases.size))
                }
            }

            if (vm.hasContactsPermission) {
                AddContactAliasForm(
                    onSearch = vm::searchContacts,
                    onConflictCheck = vm::conflictingContact,
                    onAdd = { phrase, pick -> vm.add(phrase, pick) },
                )
            } else {
                Text(
                    stringResource(R.string.contact_aliases_need_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDialog) {
        ContactAliasesDialog(
            aliases = aliases,
            onDelete = { vm.remove(it) },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ContactAliasesDialog(
    aliases: List<ContactAliasRow>,
    onDelete: (phrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val notFoundFmt = stringResource(R.string.contact_aliases_not_found)
    AliasRowsDialog(
        title = stringResource(R.string.contact_aliases_header),
        rows = aliases,
        primaryOf = { it.phrase },
        secondaryOf = { row ->
            if (row.foundInContacts) SecondaryLine(row.displayName)
            else SecondaryLine(String.format(notFoundFmt, row.displayName), warn = true)
        },
        deleteContentDescription = stringResource(R.string.contact_aliases_delete),
        doneLabel = stringResource(R.string.contact_aliases_done),
        onDelete = { onDelete(it.phrase) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddContactAliasForm(
    onSearch: suspend (String) -> List<PickableContact>,
    onConflictCheck: suspend (String) -> String?,
    onAdd: (phrase: String, pick: PickableContact) -> Unit,
) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var contactQuery by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<PickableContact?>(null) }
    var matches by remember { mutableStateOf<List<PickableContact>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Re-run the conflict check whenever the phrase field changes.
    // Cheap query (LIKE %phrase% + score filter, indexed) so debouncing
    // isn't worth the complexity — every keystroke re-runs in the IO
    // dispatcher.
    LaunchedEffect(phrase) {
        conflict = if (phrase.isBlank()) null else onConflictCheck(phrase)
    }

    // Re-query whenever the user edits the contact field. Each
    // keystroke launches a fresh coroutine — cheap since the LIKE
    // query is indexed + bounded to MAX_PICKER_RESULTS rows.
    LaunchedEffect(contactQuery) {
        if (contactQuery.isBlank()) {
            matches = emptyList()
        } else {
            matches = onSearch(contactQuery)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text(stringResource(R.string.contact_aliases_phrase_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        conflict?.let { name ->
            Text(
                stringResource(R.string.contact_aliases_conflict_warning, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = contactQuery,
                onValueChange = {
                    contactQuery = it
                    selected = null // typing again invalidates the prior pick
                    expanded = true
                },
                label = { Text(stringResource(R.string.contact_aliases_contact_label)) },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth(),
            )
            // Only show the dropdown when there's something to show.
            // SubcomposeLayout (LazyColumn) doesn't measure inside an
            // ExposedDropdownMenu — eager Composables match the
            // AppAliases card's pattern + the matches list is bounded
            // to MAX_PICKER_RESULTS so the overhead is negligible.
            if (matches.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    matches.forEach { pick ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(pick.displayName)
                                    Text(
                                        pick.number,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                contactQuery = pick.displayName
                                selected = pick
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    onAdd(phrase.trim(), selected!!)
                    phrase = ""
                    contactQuery = ""
                    selected = null
                    matches = emptyList()
                }
            },
            enabled = phrase.isNotBlank() && selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.contact_aliases_add))
        }
    }
}