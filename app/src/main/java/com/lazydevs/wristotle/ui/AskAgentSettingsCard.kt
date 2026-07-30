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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.nlu.settings.AskAgentSettings
import com.lazydevs.wristotle.speech.nlu.agent.LlmProvider
import com.lazydevs.wristotle.ui.components.PasswordField

/**
 * Settings → ✨ Ask Agent card. Picks the LLM provider for the
 * AskAgent voice intent ("ask agent …", "ask claude …") and holds the
 * per-provider API key + model id.
 *
 * Reads + writes directly through [AskAgentSettings] — no dedicated VM
 * because the card is just an observer with a handful of setters
 * (mirrors WeatherSettingsCard's pattern). The active client is
 * rebuilt per call by [com.lazydevs.wristotle.handlers.AskAgentHandler]
 * so a key/model edit takes effect on the next voice query.
 *
 * No watch-side mirror — these settings are companion-local. Phase B2
 * will layer MCP tool selection on top; the picker shape here is
 * unchanged.
 */
@Composable
fun AskAgentSettingsCard(settings: AskAgentSettings) {
    val provider by settings.provider.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_askagent_header),
                description = stringResource(R.string.settings_askagent_desc),
            )

            // ── Provider ────────────────────────────────────────────────────
            Text(
                stringResource(R.string.settings_askagent_provider_label),
                style = MaterialTheme.typography.titleSmall,
            )
            RadioRow(
                label = stringResource(R.string.settings_askagent_provider_anthropic),
                selected = provider == LlmProvider.ANTHROPIC,
                onSelect = { settings.setProvider(LlmProvider.ANTHROPIC) },
            )
            RadioRow(
                label = stringResource(R.string.settings_askagent_provider_openai),
                selected = provider == LlmProvider.OPENAI_COMPATIBLE,
                onSelect = { settings.setProvider(LlmProvider.OPENAI_COMPATIBLE) },
            )

            Spacer(Modifier.height(8.dp))

            // ── Per-provider fields ─────────────────────────────────────────
            when (provider) {
                LlmProvider.ANTHROPIC -> AnthropicFields(settings)
                LlmProvider.OPENAI_COMPATIBLE -> OpenAiFields(settings)
            }

            Spacer(Modifier.height(8.dp))

            // ── System prompt (both providers) ──────────────────────────────
            // Pre-populated with the watch-friendly baseline so the user
            // sees what's actually being sent (no hidden prepend); editable
            // freely, with a one-tap reset if they want the baseline back.
            val systemPrompt by settings.systemPrompt.collectAsState()
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = settings::setSystemPrompt,
                label = { Text(stringResource(R.string.settings_askagent_system_prompt_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                stringResource(R.string.settings_askagent_system_prompt_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = settings::resetSystemPromptToDefault,
                enabled = systemPrompt != AskAgentSettings.DEFAULT_SYSTEM_PROMPT,
            ) {
                Text(stringResource(R.string.settings_askagent_system_prompt_reset))
            }

            Spacer(Modifier.height(8.dp))

            // ── Custom trigger words ────────────────────────────────────────
            // User-added subjects on top of the built-ins (agent / claude /
            // ai / ...). One per line in the UI; persisted lower-cased and
            // de-duplicated. Routing + slot extraction read the live list,
            // so a Settings save takes effect on the next voice query.
            //
            // Bound to a LOCAL editable copy, not the sanitised StateFlow:
            // sanitising on every keystroke stripped the trailing newline the
            // instant you pressed Enter, so a second trigger word could never be
            // started. setCustomTriggers still sanitises for storage + routing.
            // Initialised once (unkeyed remember).
            val customTriggers by settings.customTriggers.collectAsState()
            var triggerText by remember { mutableStateOf(customTriggers.joinToString("\n")) }
            OutlinedTextField(
                value = triggerText,
                onValueChange = {
                    triggerText = it
                    settings.setCustomTriggers(it)
                },
                label = { Text(stringResource(R.string.settings_askagent_triggers_label)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                stringResource(R.string.settings_askagent_triggers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // ── Response timeout ────────────────────────────────────────────
            // Raised for slow local reasoning models (Gemma/R1-style thinking)
            // that the old hardcoded 14 s timed out mid-generation. Clamped in
            // the setter; the watch is kept alive past its 15 s ceiling by the
            // handler's agent_status heartbeat.
            val responseTimeout by settings.responseTimeoutSec.collectAsState()
            var timeoutText by remember(responseTimeout) { mutableStateOf(responseTimeout.toString()) }
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { txt ->
                    timeoutText = txt.filter { it.isDigit() }.take(3)
                    timeoutText.toIntOrNull()?.let(settings::setResponseTimeoutSec)
                },
                label = { Text(stringResource(R.string.settings_askagent_timeout_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_askagent_timeout_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnthropicFields(settings: AskAgentSettings) {
    val apiKey by settings.anthropicApiKey.collectAsState()
    val model by settings.anthropicModel.collectAsState()
    val webSearch by settings.anthropicWebSearch.collectAsState()

    PasswordField(
        value = apiKey,
        onChange = settings::setAnthropicApiKey,
        label = stringResource(R.string.settings_askagent_anthropic_key_label),
    )
    Text(
        stringResource(R.string.settings_askagent_anthropic_key_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = model,
        onValueChange = settings::setAnthropicModel,
        label = { Text(stringResource(R.string.settings_askagent_model_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_askagent_anthropic_web_search_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = webSearch, onCheckedChange = settings::setAnthropicWebSearch)
    }
    Text(
        stringResource(R.string.settings_askagent_anthropic_web_search_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OpenAiFields(settings: AskAgentSettings) {
    val endpoint by settings.openaiEndpoint.collectAsState()
    val apiKey by settings.openaiApiKey.collectAsState()
    val model by settings.openaiModel.collectAsState()

    OutlinedTextField(
        value = endpoint,
        onValueChange = settings::setOpenAiEndpoint,
        label = { Text(stringResource(R.string.settings_askagent_openai_endpoint_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.settings_askagent_openai_endpoint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PasswordField(
        value = apiKey,
        onChange = settings::setOpenAiApiKey,
        label = stringResource(R.string.settings_askagent_openai_key_label),
    )
    OutlinedTextField(
        value = model,
        onValueChange = settings::setOpenAiModel,
        label = { Text(stringResource(R.string.settings_askagent_model_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}