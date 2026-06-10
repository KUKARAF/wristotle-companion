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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderMode
import com.lazydevs.wristotle.speech.nlu.settings.TtsProviderSettings
import com.lazydevs.wristotle.tts.HttpTtsClient
import com.lazydevs.wristotle.tts.LocalTtsProvider
import com.lazydevs.wristotle.tts.TtsStreamer
import com.lazydevs.wristotle.ui.components.PasswordField
import kotlinx.coroutines.launch

/**
 * Display rows for the per-intent checkboxes, grouped so the user can
 * scan by category. The id strings come from [TtsProviderSettings]
 * constants so handlers can check the toggle with the same key the UI
 * writes.
 */
private data class IntentGroup(val title: String, val entries: List<Pair<String, String>>)

private val INTENT_GROUPS = listOf(
    IntentGroup(
        "Long replies",
        listOf(
            TtsProviderSettings.INTENT_ASK_AGENT to "Ask Agent",
            TtsProviderSettings.INTENT_MORNING_BRIEF to "Morning brief",
        ),
    ),
    IntentGroup(
        "Quick lookups",
        listOf(
            TtsProviderSettings.INTENT_WEATHER to "Weather",
            TtsProviderSettings.INTENT_WORLD_TIME to "World time",
            TtsProviderSettings.INTENT_TIME to "Time",
            TtsProviderSettings.INTENT_BATTERY to "Battery",
            TtsProviderSettings.INTENT_CALCULATE to "Calculator",
        ),
    ),
    IntentGroup(
        "Communications",
        listOf(
            TtsProviderSettings.INTENT_CALL to "Call confirmations",
            TtsProviderSettings.INTENT_SEND_MESSAGE to "Message confirmations",
        ),
    ),
    IntentGroup(
        "Time & schedule",
        listOf(
            TtsProviderSettings.INTENT_REMINDER to "Reminder confirmations",
            TtsProviderSettings.INTENT_SET_ALARM to "Alarm confirmations",
            TtsProviderSettings.INTENT_SET_TIMER to "Timer confirmations",
            TtsProviderSettings.INTENT_CALENDAR to "Calendar list",
            TtsProviderSettings.INTENT_CREATE_EVENT to "Event confirmations",
        ),
    ),
    IntentGroup(
        "Capture",
        listOf(
            TtsProviderSettings.INTENT_NOTE to "Note confirmations",
            TtsProviderSettings.INTENT_ADD_TASK to "Task add confirmations",
            TtsProviderSettings.INTENT_LIST_TASKS to "Task list",
        ),
    ),
)

/**
 * Settings → 🔊 Speech (or wherever it lands) — chooses how Ask-Agent and
 * other on-watch readouts are synthesized. Mirrors [SttProviderCard] so
 * users see the same mental model on the input + output sides of the
 * voice loop.
 *
 *  - **Master toggle** — when off, no watch TTS is attempted regardless
 *    of mode. Default off (opt-in feature, emery-only speaker).
 *  - **Local only** — Android `TextToSpeech` (whatever engine the device
 *    has installed). No network.
 *  - **Local primary** — Android first, HTTP fallback on init / synth failure.
 *  - **Cloud primary** — HTTP first, Android fallback on transport / 4xx /
 *    5xx failure. Right default for users with a self-hosted server or
 *    paid API who want best-quality output but don't want to lose voice
 *    on a network blip.
 *
 * The HTTP block covers every OpenAI-compatible `/v1/audio/speech` provider:
 * OpenAI itself (alloy / nova / shimmer / …), self-hosted Piper /
 * OpenedAI Speech, LiteLLM proxies. Empty defaults by design (see
 * `feedback_no_prefilled_provider_defaults`).
 */
@Composable
fun TtsProviderCard(settings: TtsProviderSettings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as WristotleApplication
    val scope = rememberCoroutineScope()

    val enabled by settings.enabled.collectAsState()
    val mode by settings.mode.collectAsState()
    val baseUrl by settings.httpBaseUrl.collectAsState()
    val apiKey by settings.httpApiKey.collectAsState()
    val model by settings.httpModel.collectAsState()
    val voice by settings.httpVoice.collectAsState()
    val intents by settings.intentsEnabled.collectAsState()

    var status by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Speak on watch", style = MaterialTheme.typography.titleMedium)
            Text(
                "Read Ask Agent replies on the Pebble Time 2 speaker (emery only). Off until you turn it on.",
                style = MaterialTheme.typography.bodySmall,
            )

            // ── Master toggle ─────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { settings.setEnabled(it) })
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (enabled) "Enabled" else "Disabled")
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Speak which replies?", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Per-intent — pick what the watch reads aloud. Nothing is spoken by default.",
                    style = MaterialTheme.typography.bodySmall,
                )
                INTENT_GROUPS.forEachIndexed { idx, group ->
                    if (idx > 0) Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    group.entries.forEach { (id, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = id in intents,
                                onCheckedChange = { settings.setIntentEnabled(id, it) },
                            )
                            Text(label)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Mode", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    label = "Local only — Android TTS",
                    selected = mode == TtsProviderMode.LOCAL_ONLY,
                    onSelect = { settings.setMode(TtsProviderMode.LOCAL_ONLY) },
                )
                RadioRow(
                    label = "Local primary — Android first, HTTP fallback",
                    selected = mode == TtsProviderMode.LOCAL_PRIMARY,
                    onSelect = { settings.setMode(TtsProviderMode.LOCAL_PRIMARY) },
                )
                RadioRow(
                    label = "Cloud primary — HTTP first, Android fallback",
                    selected = mode == TtsProviderMode.CLOUD_PRIMARY,
                    onSelect = { settings.setMode(TtsProviderMode.CLOUD_PRIMARY) },
                )

                if (mode != TtsProviderMode.LOCAL_ONLY) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("HTTP endpoint", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Any OpenAI-compatible /v1/audio/speech provider — OpenAI, OpenedAI Speech, LiteLLM in front of Piper.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { settings.setHttpBaseUrl(it) },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    PasswordField(
                        value = apiKey,
                        onChange = { settings.setHttpApiKey(it) },
                        label = "API key (optional for self-hosted)",
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { settings.setHttpModel(it) },
                        label = { Text("Model") },
                        placeholder = { Text("tts-1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = voice,
                        onValueChange = { settings.setHttpVoice(it) },
                        label = { Text("Voice") },
                        placeholder = { Text("alloy") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // ── Test "Hello from Wristotle" ───────────────────────
                // Tests the PRIMARY provider for the selected mode only.
                // If the cloud endpoint is misconfigured we want the test
                // to fail visibly here, not silently fall back to Android
                // TTS and look like the cloud worked.
                Spacer(modifier = Modifier.height(8.dp))
                val primaryLabel = when (mode) {
                    TtsProviderMode.LOCAL_ONLY, TtsProviderMode.LOCAL_PRIMARY -> "Android TTS"
                    TtsProviderMode.CLOUD_PRIMARY -> "HTTP endpoint"
                }
                OutlinedButton(
                    onClick = {
                        status = "testing $primaryLabel…"
                        scope.launch {
                            val primary = when (mode) {
                                TtsProviderMode.LOCAL_ONLY,
                                TtsProviderMode.LOCAL_PRIMARY -> LocalTtsProvider(context)
                                TtsProviderMode.CLOUD_PRIMARY -> HttpTtsClient(settings)
                            }
                            val streamer = TtsStreamer(context, app.transport, primary)
                            val reason = runCatching { streamer.speak("Hello from Wristotle.") }
                                .getOrElse {
                                    status = "$primaryLabel error: ${it.message ?: it.javaClass.simpleName}"
                                    return@launch
                                }
                            status = if (reason == null) {
                                "$primaryLabel: ok — check watch"
                            } else {
                                "$primaryLabel failed: $reason"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test primary on watch") }

                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

