// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.sportskapi.SportDataSource
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.nlu.settings.SportSettings
import com.lazydevs.wristotle.speech.nlu.sport.SportFormat
import kotlinx.coroutines.launch

/**
 * Settings card for the Sports feature:
 *  - **Favorites** — saved teams so "did we win" works without naming a team.
 *  - **Sport priority** — a reorderable list; when a team name is shared across
 *    sports (e.g. "City"), the higher-ranked sport wins. Feeds the resolver.
 */
@Composable
fun SportSettingsCard(settings: SportSettings, source: SportDataSource) {
    val favorites by settings.favorites.collectAsState()
    val priority by settings.preferredSports.collectAsState()
    val excluded by settings.excludedSports.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_section_sport),
                description = stringResource(R.string.sport_settings_desc),
            )

            // ── Favorites ────────────────────────────────────────────────
            Text("Favorite teams", style = MaterialTheme.typography.titleSmall)
            if (favorites.isEmpty()) {
                Text(
                    "No favorites yet. Add one so \"did we win?\" works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            favorites.forEach { fav ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${fav.name}  ·  ${fav.league}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { settings.removeFavorite(fav.id) }) { Text("Remove") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; status = null },
                    label = { Text("Add a team") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val name = input.trim()
                        scope.launch {
                            val subject = source.resolveTeam(name, priority, excluded.toList())
                            if (subject != null) {
                                settings.addFavorite(subject)
                                input = ""
                                status = null
                            } else {
                                status = "Couldn't find \"$name\""
                            }
                        }
                    },
                ) { Text("Add") }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))

            // ── Sport priority ───────────────────────────────────────────
            Text("Sport priority", style = MaterialTheme.typography.titleSmall)
            Text(
                "When a team name is shared across sports (e.g. \"City\"), the " +
                    "sport higher in this list wins. Untick a sport to exclude it — " +
                    "you'll get no info for it and it won't match spoken names.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            priority.forEachIndexed { i, key ->
                val enabled = key !in excluded
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { settings.setSportEnabled(key, it) },
                    )
                    Text(
                        "${i + 1}.  ${sportLabel(key)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    IconButton(
                        enabled = i > 0,
                        onClick = { settings.setPreferredSports(priority.moved(i, i - 1)) },
                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                    IconButton(
                        enabled = i < priority.size - 1,
                        onClick = { settings.setPreferredSports(priority.moved(i, i + 1)) },
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Sports data (config) ─────────────────────────────────────
            // Leagues, team names and provider fixes update automatically in the
            // background; this lets users pull the latest immediately. The button
            // enables ONLY when a newer config is actually available.
            var dataVersion by remember { mutableStateOf(source.configVersion()) }
            var updateAvailable by remember { mutableStateOf(false) }
            var dataBusy by remember { mutableStateOf(false) }
            var dataMessage by remember { mutableStateOf("Checking for updates…") }

            suspend fun checkForUpdate() {
                dataBusy = true
                dataMessage = "Checking for updates…"
                updateAvailable = source.isConfigUpdateAvailable()
                dataMessage = if (updateAvailable) "An update is available." else "Up to date (v$dataVersion)."
                dataBusy = false
            }

            LaunchedEffect(Unit) { checkForUpdate() }

            Text("Sports data", style = MaterialTheme.typography.titleSmall)
            Text(
                "Leagues, team names and fixes update automatically. Pull the latest now:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                dataMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (updateAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = updateAvailable && !dataBusy,
                    onClick = {
                        scope.launch {
                            dataBusy = true
                            dataMessage = "Updating…"
                            if (source.refreshConfig()) {
                                dataVersion = source.configVersion()
                                updateAvailable = false
                                dataMessage = "Updated to v$dataVersion."
                            } else {
                                dataMessage = "Update failed — try again."
                            }
                            dataBusy = false
                        }
                    },
                ) { Text("Update sports data") }
                TextButton(
                    enabled = !dataBusy,
                    onClick = { scope.launch { checkForUpdate() } },
                ) { Text("Check again") }
            }
        }
    }
}

/** Shared with the handler's "sport is turned off" message — one source of truth. */
private fun sportLabel(key: String): String = SportFormat.sportLabel(key)

/** Returns a copy with the item at [from] moved to index [to]. */
private fun List<String>.moved(from: Int, to: Int): List<String> {
    if (from == to || from !in indices || to !in indices) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}
