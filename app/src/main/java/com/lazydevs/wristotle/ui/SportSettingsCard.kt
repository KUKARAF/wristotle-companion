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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

/**
 * Settings card for the Sports feature:
 *  - **Favorites** — saved teams so "did we win" / "next game" work without
 *    naming a team. Add by typing a name (resolved via the sports library);
 *    remove with the row button.
 *  - **Preferred sport** — biases team-name disambiguation.
 *
 * Reads StateFlows directly off [SportSettings] (no VM). Resolution is a
 * suspend call on the provider-neutral [SportDataSource], so adds run in a
 * coroutine.
 */
@Composable
fun SportSettingsCard(settings: SportSettings, source: SportDataSource) {
    val favorites by settings.favorites.collectAsState()
    val preferred by settings.preferredSport.collectAsState()
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
                            val pref = preferred.ifBlank { null }
                            val subject = source.resolveTeam(name, pref)
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

            // ── Preferred sport ──────────────────────────────────────────
            Text("Preferred sport", style = MaterialTheme.typography.titleSmall)
            Text(
                "Helps pick the right team when a name is shared across leagues.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PREFERRED_SPORTS.forEach { (label, value) ->
                RadioRow(
                    label = label,
                    selected = preferred == value,
                    onSelect = { settings.setPreferredSport(value) },
                )
            }
        }
    }
}

private val PREFERRED_SPORTS = listOf(
    "Any" to "",
    "Soccer" to "soccer",
    "Basketball" to "basketball",
    "Football" to "football",
    "Baseball" to "baseball",
    "Hockey" to "hockey",
)
