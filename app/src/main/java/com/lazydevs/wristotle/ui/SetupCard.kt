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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.setup.Priority
import com.lazydevs.wristotle.setup.RecommendedAction
import com.lazydevs.wristotle.setup.SetupHealthProvider

/**
 * Persistent checklist for Settings → 🌟 Setup. Renders whatever
 * [SetupHealthProvider.actions] currently holds, grouped into
 * Essentials and Optional sections. When everything's complete the
 * card collapses to a one-liner.
 *
 * The "Open" button on each row drills into the action's target
 * Settings category — the user finishes the action with familiar UI,
 * the provider re-derives, and the row disappears.
 */
@Composable
fun SetupCard(
    provider: SetupHealthProvider,
    onOpenCategory: (com.lazydevs.wristotle.ui.SettingsCategory) -> Unit,
) {
    val actions by provider.actions.collectAsState()

    // Re-derive on first compose — covers the user returning from a
    // sub-screen where they completed an action (permission grant, app
    // scan, model download). Without this the list stays stale until
    // the next process recreate.
    LaunchedEffect(Unit) { provider.refresh() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.setup_card_header),
                description = stringResource(R.string.setup_card_desc),
            )

            if (actions.isEmpty()) {
                Text(
                    stringResource(R.string.setup_card_all_done),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val essentials = actions.filter { it.priority == Priority.Essential }
            val quality = actions.filter { it.priority == Priority.Quality }

            if (essentials.isNotEmpty()) {
                SetupSection(
                    titleRes = R.string.setup_section_essentials,
                    actions = essentials,
                    onOpenCategory = onOpenCategory,
                )
            }
            if (quality.isNotEmpty()) {
                if (essentials.isNotEmpty()) Spacer(Modifier.height(4.dp))
                SetupSection(
                    titleRes = R.string.setup_section_quality,
                    actions = quality,
                    onOpenCategory = onOpenCategory,
                )
            }
        }
    }
}

@Composable
private fun SetupSection(
    titleRes: Int,
    actions: List<RecommendedAction>,
    onOpenCategory: (com.lazydevs.wristotle.ui.SettingsCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        actions.forEach { action ->
            SetupRow(action = action, onOpen = { onOpenCategory(action.drillTarget) })
        }
    }
}

@Composable
private fun SetupRow(
    action: RecommendedAction,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(action.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onOpen) {
                Text(stringResource(R.string.setup_action_open_button))
            }
        }
        Text(
            stringResource(action.rationaleRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
