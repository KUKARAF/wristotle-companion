// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings
import com.lazydevs.wristotle.speech.nlu.settings.HomeAssistantSettings
import com.lazydevs.wristotle.speech.nlu.slots.AskAgentTriggers
import com.lazydevs.wristotle.ui.components.PasswordField

/**
 * Settings → 🏠 Home Assistant card. Points the HomeAssistant voice intent
 * ("hey home assistant …", "home assistant …") at a self-hosted HA instance:
 * base URL + long-lived token + optional extra wake words.
 *
 * Reads + writes directly through [HomeAssistantSettings] — no VM, mirrors
 * [AskAgentSettingsCard]. [askAgentSettings] is passed only to warn when a
 * custom wake word also triggers Ask Agent (both would fire on the same word;
 * routing sends it to Home Assistant — see WatchHintRefiner).
 */
@Composable
fun HomeAssistantSettingsCard(
    settings: HomeAssistantSettings,
    askAgentSettings: AskAgentSettings,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_homeassistant_header),
                description = stringResource(R.string.settings_homeassistant_desc),
            )

            // ── Base URL ────────────────────────────────────────────────────
            val baseUrl by settings.baseUrl.collectAsState()
            OutlinedTextField(
                value = baseUrl,
                onValueChange = settings::setBaseUrl,
                label = { Text(stringResource(R.string.settings_homeassistant_url_label)) },
                placeholder = { Text("http://homeassistant.local:8123") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_homeassistant_url_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // ── Long-lived access token ─────────────────────────────────────
            val token by settings.token.collectAsState()
            PasswordField(
                value = token,
                onChange = settings::setToken,
                label = stringResource(R.string.settings_homeassistant_token_label),
            )
            Text(
                stringResource(R.string.settings_homeassistant_token_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // ── Custom trigger words ────────────────────────────────────────
            // Extra wake words on top of the built-ins. One per line; persisted
            // lower-cased + de-duplicated. Routing + slot extraction read the
            // live list, so a save takes effect on the next voice command.
            val customTriggers by settings.customTriggers.collectAsState()
            val triggerText = customTriggers.joinToString("\n")
            OutlinedTextField(
                value = triggerText,
                onValueChange = settings::setCustomTriggers,
                label = { Text(stringResource(R.string.settings_homeassistant_triggers_label)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                stringResource(R.string.settings_homeassistant_triggers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Warn per colliding word: a wake word set for BOTH Ask Agent and
            // Home Assistant routes to Home Assistant (it wins the tie), so the
            // Ask Agent binding for that word silently stops working.
            val askAgentSubjects by askAgentSettings.customTriggers.collectAsState()
            val askAgentAll = remember(askAgentSubjects) {
                (AskAgentTriggers.DEFAULT_SUBJECTS + askAgentSubjects).toSet()
            }
            customTriggers.filter { it in askAgentAll }.forEach { word ->
                Text(
                    stringResource(R.string.settings_homeassistant_trigger_collision, word),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Response timeout ────────────────────────────────────────────
            val responseTimeout by settings.responseTimeoutSec.collectAsState()
            var timeoutText by remember(responseTimeout) { mutableStateOf(responseTimeout.toString()) }
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { txt ->
                    timeoutText = txt.filter { it.isDigit() }.take(3)
                    timeoutText.toIntOrNull()?.let(settings::setResponseTimeoutSec)
                },
                label = { Text(stringResource(R.string.settings_homeassistant_timeout_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_homeassistant_timeout_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
