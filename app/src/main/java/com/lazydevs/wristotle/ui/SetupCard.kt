// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.setup.Priority
import com.lazydevs.wristotle.setup.RecommendedAction
import com.lazydevs.wristotle.setup.SetupDrillTarget
import com.lazydevs.wristotle.setup.SetupHealthProvider
import com.lazydevs.wristotle.setup.SetupSettings
import com.lazydevs.wristotle.ui.components.ConfirmDialog
import com.lazydevs.wristotle.ui.nav.Screen

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
    setupSettings: SetupSettings,
    /** Drills into a Settings sub-category (e.g. Models, Learning). */
    onOpenCategory: (com.lazydevs.wristotle.ui.SettingsCategory) -> Unit,
    /** Switches to a top-level bottom-nav tab outside the Settings
     *  drill-down (today: only [Screen.Permissions]). */
    onOpenTopLevelTab: (Screen) -> Unit,
) {
    val actions by provider.actions.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }

    // Re-derive on every ON_RESUME so the user returning from a
    // sub-screen where they completed an action (permission grant,
    // model download, alias added) sees the row disappear without
    // waiting for the next process recreate. LaunchedEffect(Unit)
    // alone fires only on first compose; the lifecycle observer
    // catches every subsequent resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) provider.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    onOpenTopLevelTab = onOpenTopLevelTab,
                )
            }
            if (quality.isNotEmpty()) {
                if (essentials.isNotEmpty()) Spacer(Modifier.height(4.dp))
                SetupSection(
                    titleRes = R.string.setup_section_quality,
                    actions = quality,
                    onOpenCategory = onOpenCategory,
                    onOpenTopLevelTab = onOpenTopLevelTab,
                )
            }

            // Reset link at the bottom — replays the welcome wizard on
            // the next cold start. Hidden behind a confirm because most
            // users don't need it; useful for the "skipped too quickly
            // and want to re-walk" case and for testing.
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showResetConfirm = true }) {
                Text(stringResource(R.string.setup_card_show_welcome_again))
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.setup_reset_confirm_title),
            message = stringResource(R.string.setup_reset_confirm_body),
            confirmLabel = stringResource(R.string.setup_reset_confirm_proceed),
            onConfirm = {
                setupSettings.reshowWelcomeWizard()
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false },
        )
    }
}

@Composable
private fun SetupSection(
    titleRes: Int,
    actions: List<RecommendedAction>,
    onOpenCategory: (com.lazydevs.wristotle.ui.SettingsCategory) -> Unit,
    onOpenTopLevelTab: (Screen) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        actions.forEach { action ->
            SetupRow(
                action = action,
                onOpen = {
                    when (val target = action.drillTarget) {
                        is SetupDrillTarget.SettingsSub -> onOpenCategory(target.category)
                        is SetupDrillTarget.TopLevelTab -> onOpenTopLevelTab(target.screen)
                    }
                },
            )
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