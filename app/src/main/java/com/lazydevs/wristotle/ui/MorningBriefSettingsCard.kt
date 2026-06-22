// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.speech.nlu.briefing.BriefSection
import com.lazydevs.wristotle.speech.nlu.settings.MorningBriefSettings

/**
 * Settings → 🔔 Notifications sub-card (next to the brief's notification-log
 * toggle): pick which sections the spoken Morning Brief ("what's on my plate
 * today") includes. Mirrors the Watch-cards /
 * Speak pickers — a [FilterChip] per [BriefSection] plus Select all / Deselect
 * all. Writes [MorningBriefSettings] directly (the handler reads the same
 * store), so changes take effect on the next brief with no restart. Turning a
 * section off also skips reading its data — notably Messages, which otherwise
 * reads active notifications.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MorningBriefSettingsCard(settings: MorningBriefSettings) {
    val disabled by settings.disabledSections.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = "Morning Brief",
                description = "Ask \"what's on my plate today\" and the watch reads a " +
                    "rollup of today. Pick which sections it includes; the others are " +
                    "skipped entirely (turning off Messages also stops it reading your " +
                    "notifications). All sections are on by default.",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { settings.setEnabledSections(BriefSection.entries.toSet()) }) {
                    Text("Select all")
                }
                OutlinedButton(onClick = { settings.setEnabledSections(emptySet()) }) {
                    Text("Deselect all")
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BriefSection.entries.forEach { section ->
                    val enabled = section.key !in disabled
                    FilterChip(
                        selected = enabled,
                        onClick = { settings.setEnabled(section, on = !enabled) },
                        label = { Text(section.label) },
                    )
                }
            }
        }
    }
}
