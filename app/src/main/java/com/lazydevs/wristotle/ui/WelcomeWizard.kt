package com.lazydevs.wristotle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.setup.RecommendedAction
import com.lazydevs.wristotle.setup.SetupDrillTarget
import com.lazydevs.wristotle.ui.nav.Screen

/**
 * Multi-step welcome wizard that surfaces the user's pending Essential
 * recommended actions one screen at a time. Quality-tier items are NOT
 * surfaced here — they live in the persistent Settings → 🌟 Setup card
 * for users who want to explore further.
 *
 * Step shape:
 *  - [0]    Welcome — intro + "Get started" / "Skip wizard"
 *  - [1..N] One screen per pending essential action
 *  - [N+1]  Final — "All set" + "Done"
 *
 * Dynamic: a power user with no essentials pending sees Welcome →
 * Final and the wizard is two taps.
 *
 * Per-action controls:
 *  - **Skip step** — advance within the wizard, leave the action
 *    pending. The user can finish it from Settings → 🌟 Setup later.
 *  - **Open Settings** — dismisses the wizard and drops the user on
 *    the action's target sub-screen. They can re-trigger the wizard
 *    via Settings → 🌟 Setup → "Show welcome again".
 *  - **Skip wizard** — sets the dismissed flag and closes immediately.
 */
@Composable
fun WelcomeWizard(
    essentialActions: List<RecommendedAction>,
    /** Current step. **Hoisted to the caller** so the wizard's progress
     *  survives temporary hide/show cycles — e.g. when the user taps
     *  "Open settings", the wizard is removed from the tree while the
     *  user finishes the action; when it re-appears, the caller keeps
     *  the step they were on (or implicitly advances because the
     *  essentials list shrunk). 0 = Welcome; 1..N = actions; N+1 = Final. */
    stepIndex: Int,
    onStepIndexChange: (Int) -> Unit,
    onSkipWizard: () -> Unit,
    /** Drills into a Settings sub-category. */
    onOpenCategory: (SettingsCategory) -> Unit,
    /** Switches to a top-level bottom-nav tab (today: only
     *  [Screen.Permissions]). */
    onOpenTopLevelTab: (Screen) -> Unit,
    onFinish: () -> Unit,
) {
    val totalSteps = essentialActions.size + 2 // welcome + actions + final
    val clampedIndex = stepIndex.coerceAtMost(totalSteps - 1)
    val isWelcome = clampedIndex == 0
    val isFinal = clampedIndex == totalSteps - 1
    val currentAction = if (!isWelcome && !isFinal) essentialActions[clampedIndex - 1] else null

    Dialog(
        onDismissRequest = { /* Disable accidental dismiss — user must use a button. */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Block the system back gesture inside the wizard — accidental
        // back-presses on a multi-step flow are easy to make and the
        // "Skip wizard" button is the right way out.
        BackHandler(enabled = true) { /* swallow */ }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Progress bar — visual hint of how many steps are left.
                // Hidden on the welcome screen (zero progress feels off).
                if (!isWelcome) {
                    LinearProgressIndicator(
                        progress = { (clampedIndex.toFloat() / (totalSteps - 1).coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        isWelcome -> WelcomeStep(pendingCount = essentialActions.size)
                        isFinal -> FinalStep(skippedAny = clampedIndex > totalSteps - 1)
                        currentAction != null -> ActionStep(action = currentAction)
                    }
                }

                WizardControls(
                    isWelcome = isWelcome,
                    isFinal = isFinal,
                    onSkipStep = { onStepIndexChange(clampedIndex + 1) },
                    onOpenSettings = {
                        // The Open Settings button is only rendered on
                        // action steps, so currentAction is non-null
                        // here. Drill into the action's target — either
                        // a Settings sub-category or a top-level tab.
                        currentAction?.let { action ->
                            when (val target = action.drillTarget) {
                                is SetupDrillTarget.SettingsSub -> onOpenCategory(target.category)
                                is SetupDrillTarget.TopLevelTab -> onOpenTopLevelTab(target.screen)
                            }
                        }
                    },
                    onSkipWizard = onSkipWizard,
                    onAdvance = {
                        if (clampedIndex < totalSteps - 1) onStepIndexChange(clampedIndex + 1)
                        else onFinish()
                    },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(pendingCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.setup_wizard_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.setup_wizard_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (pendingCount > 0) {
            Text(
                stringResource(R.string.setup_wizard_welcome_count, pendingCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.setup_wizard_welcome_nothing_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionStep(action: RecommendedAction) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(action.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(action.rationaleRes),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FinalStep(skippedAny: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.setup_wizard_final_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.setup_wizard_final_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.setup_wizard_final_return_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WizardControls(
    isWelcome: Boolean,
    isFinal: Boolean,
    onSkipStep: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipWizard: () -> Unit,
    onAdvance: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            isWelcome -> {
                Button(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_wizard_welcome_get_started))
                }
                TextButton(onClick = onSkipWizard, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_wizard_skip_all))
                }
            }
            isFinal -> {
                Button(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_wizard_done))
                }
            }
            else -> {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_wizard_open_settings))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onSkipStep,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.setup_wizard_skip_step)) }
                }
                TextButton(onClick = onSkipWizard, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_wizard_skip_all))
                }
            }
        }
    }
}
