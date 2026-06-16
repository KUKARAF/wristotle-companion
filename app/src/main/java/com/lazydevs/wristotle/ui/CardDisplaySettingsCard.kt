// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.speech.nlu.settings.CardSettings

/**
 * Settings → ⌚ Watch sub-card: per-card-kind on/off for the watch's
 * full-screen cards. Mirrors the Speak per-intent picker — noun-grouped
 * [FilterChip]s plus Show all / Hide all. Reads/writes [CardSettings]
 * directly (the dispatch site consults the same store), so toggles take
 * effect on the next query with no restart.
 */
private data class CardGroup(val title: String, val entries: List<Pair<String, String>>)

private val CARD_GROUPS = listOf(
    CardGroup(
        "Sports",
        listOf(
            CardSettings.SPORT_SCORE to "Scores",
            CardSettings.SPORT_STANDINGS to "Standings",
            CardSettings.SPORT_FIXTURE to "Fixtures",
        ),
    ),
    CardGroup(
        "Reminders",
        listOf(
            CardSettings.REMINDER_CREATE to "Create",
            CardSettings.REMINDER_RESCHEDULE to "Reschedule",
            CardSettings.REMINDER_CANCEL to "Cancel",
            CardSettings.REMINDER_LIST to "List",
        ),
    ),
    CardGroup(
        "Tasks",
        listOf(
            CardSettings.TASK_CREATE to "Create",
            CardSettings.TASK_COMPLETE to "Complete",
            CardSettings.TASK_DELETE to "Delete",
            CardSettings.TASK_LIST to "List",
        ),
    ),
    CardGroup(
        "Notes",
        listOf(
            CardSettings.NOTE_CREATE to "Create",
            CardSettings.NOTE_UPDATE to "Append",
        ),
    ),
    CardGroup(
        "Meetings",
        listOf(
            CardSettings.MEETING_CREATE to "Create",
            CardSettings.MEETING_LIST to "Next / list",
        ),
    ),
    CardGroup(
        "Alarms",
        listOf(
            CardSettings.ALARM_CREATE to "Set",
            CardSettings.ALARM_CANCEL to "Cancel",
        ),
    ),
    CardGroup(
        "Other",
        listOf(
            CardSettings.WEATHER_CURRENT to "Weather",
            CardSettings.AGENT_ANSWER to "Agent",
            CardSettings.BRIEF_READ to "Morning brief",
        ),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardDisplaySettingsCard(settings: CardSettings) {
    val disabled by settings.disabledKinds.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = "Watch cards",
                description = "When a reply has a card, the watch shows a full-screen " +
                    "card — coloured band (the operation) + icon (the feature) — on top " +
                    "of the chat; BACK returns to the conversation. Sports, meetings, and " +
                    "weather are on by default; switch on any others you'd like as cards " +
                    "(the rest stay plain chat bubbles).",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { settings.setEnabledKinds(CardSettings.ALL_KINDS) }) {
                    Text("Show all")
                }
                OutlinedButton(onClick = { settings.setEnabledKinds(emptySet()) }) {
                    Text("Hide all")
                }
            }

            CARD_GROUPS.forEachIndexed { idx, group ->
                if (idx > 0) Spacer(Modifier.height(4.dp))
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.entries.forEach { (kind, label) ->
                        val enabled = kind !in disabled
                        FilterChip(
                            selected = enabled,
                            onClick = { settings.setEnabled(kind, on = !enabled) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}
