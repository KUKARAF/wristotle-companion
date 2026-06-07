// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R

/**
 * Card for the diagnostics-export flow.
 *
 *   - Redact PII toggle (default on).
 *   - Include audio toggle (default off; disabled when conversation
 *     audio capture itself is off — there's nothing to attach).
 *   - Button: builds the bundle, copies markdown to clipboard, shows
 *     a Toast, opens the Codeberg new-issue page in the browser.
 */
@Composable
fun DiagnosticsCard(vm: DiagnosticsViewModel) {
    val redact by vm.redactPii.collectAsState()
    val includeAudio by vm.includeAudio.collectAsState()
    val audioCaptureOn by vm.audioCaptureEnabled.collectAsState()
    val isBuilding by vm.isBuilding.collectAsState()
    val context = LocalContext.current

    // Drive Toast + browser-open from VM events. LaunchedEffect with
    // a stable key so the collector restarts only on configuration
    // change, not on every recomposition.
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DiagnosticsEvent.ReadyToSubmit -> {
                    val base = context.getString(R.string.diagnostics_copied_toast)
                    val message = event.attachmentNote?.let { "$base\n$it" } ?: base
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    vm.openCodebergNewIssue(context)
                }
                is DiagnosticsEvent.Failed -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.diagnostics_failed_toast, event.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.diagnostics_header),
                description = stringResource(R.string.diagnostics_desc),
            )

            ToggleRow(
                label = stringResource(R.string.diagnostics_redact_toggle),
                checked = redact,
                enabled = true,
                onCheckedChange = vm::setRedactPii,
            )
            // Always-visible caveat under the redact toggle — the
            // redaction is partial (log section keeps contact names),
            // and users need to know before they paste anywhere public.
            Text(
                stringResource(R.string.diagnostics_redact_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                label = stringResource(R.string.diagnostics_include_audio_toggle),
                checked = includeAudio && audioCaptureOn,
                // Toggle is disabled when the underlying capture is off —
                // we'd have no .wav files to attach. Settings → Conversation
                // → Audio is the place to flip it on.
                enabled = audioCaptureOn,
                onCheckedChange = vm::setIncludeAudio,
            )

            Button(
                onClick = { vm.exportAndOpenIssue() },
                enabled = !isBuilding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isBuilding) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    if (isBuilding) stringResource(R.string.diagnostics_building)
                    else stringResource(R.string.diagnostics_open_issue_button)
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}