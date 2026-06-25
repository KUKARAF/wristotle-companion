// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.lazydevs.wristotle.tts.TtsStreamer
import com.lazydevs.wristotle.ui.components.PasswordField
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Display rows for the per-intent checkboxes, grouped so the user can
 * scan by category. The id strings come from [TtsProviderSettings]
 * constants so handlers can check the toggle with the same key the UI
 * writes.
 */
private data class IntentGroup(val title: String, val entries: List<Pair<String, String>>)

private val INTENT_GROUPS = listOf(
    IntentGroup(
        "Ask Agent",
        listOf(TtsProviderSettings.INTENT_ASK_AGENT to "Reply"),
    ),
    IntentGroup(
        "Morning brief",
        listOf(TtsProviderSettings.INTENT_MORNING_BRIEF to "Read out"),
    ),
    IntentGroup(
        "Reminders",
        listOf(
            TtsProviderSettings.INTENT_REMINDER to "Create",
            TtsProviderSettings.INTENT_LIST_REMINDERS to "List",
            TtsProviderSettings.INTENT_CANCEL to "Cancel",
            TtsProviderSettings.INTENT_RESCHEDULE to "Reschedule",
        ),
    ),
    IntentGroup(
        "Alarms",
        listOf(
            TtsProviderSettings.INTENT_SET_ALARM to "Set",
            TtsProviderSettings.INTENT_CANCEL_ALARM to "Cancel",
        ),
    ),
    IntentGroup(
        "Timer",
        listOf(TtsProviderSettings.INTENT_SET_TIMER to "Set"),
    ),
    IntentGroup(
        "Tasks",
        listOf(
            TtsProviderSettings.INTENT_ADD_TASK to "Add",
            TtsProviderSettings.INTENT_LIST_TASKS to "List",
            TtsProviderSettings.INTENT_COMPLETE_TASK to "Complete",
            TtsProviderSettings.INTENT_DELETE_TASK to "Delete",
        ),
    ),
    IntentGroup(
        "Notes",
        listOf(
            TtsProviderSettings.INTENT_NOTE to "Create",
            TtsProviderSettings.INTENT_APPEND_NOTE to "Append",
        ),
    ),
    IntentGroup(
        "Calendar",
        listOf(
            TtsProviderSettings.INTENT_CREATE_EVENT to "Create event",
            TtsProviderSettings.INTENT_CALENDAR to "List events",
        ),
    ),
    IntentGroup(
        "Messaging",
        listOf(TtsProviderSettings.INTENT_SEND_MESSAGE to "Send"),
    ),
    IntentGroup(
        "Calls",
        listOf(TtsProviderSettings.INTENT_CALL to "Place"),
    ),
    IntentGroup(
        "Lookups",
        listOf(
            TtsProviderSettings.INTENT_WEATHER to "Weather",
            TtsProviderSettings.INTENT_WORLD_TIME to "World time",
            TtsProviderSettings.INTENT_TIME to "Time",
            TtsProviderSettings.INTENT_BATTERY to "Battery",
            TtsProviderSettings.INTENT_CALCULATE to "Calculator",
            TtsProviderSettings.INTENT_SPORT to "Sports",
            TtsProviderSettings.INTENT_SHOW_CODE to "Show code",
        ),
    ),
)

private val ALL_INTENT_IDS: Set<String> =
    INTENT_GROUPS.flatMap { group -> group.entries.map { it.first } }.toSet()

private const val DEFAULT_TEST_PHRASE =
    "Hello, this is Wristotle speaking from your watch. " +
    "The current time is 3:42, the weather is partly cloudy with a high of 72 degrees, " +
    "and you have 4 unread messages waiting for you."

/**
 * Settings → 🔊 Speech card. Two sections, stacked top-down:
 *
 *  1. **Mode + testing** (always visible). Picks the provider mode + HTTP
 *     config + a free-form test phrase + four test buttons (watch primary
 *     / 440 Hz watch tone / phone playback of the same PCM / 440 Hz phone
 *     tone). Lets the user configure and verify the pipeline end-to-end
 *     without having to first toggle the master switch on.
 *
 *  2. **Speak on watch** (master toggle + per-intent picker). The actual
 *     dispatch hook only fires TTS when the master is on AND the routed
 *     intent has its per-intent checkbox enabled.
 *
 * The Mode block covers every OpenAI-compatible `/v1/audio/speech`
 * provider (OpenAI, OpenedAI Speech, LiteLLM in front of Piper, …). Empty
 * defaults by design — see `feedback_no_prefilled_provider_defaults`.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    var testPhrase by remember { mutableStateOf(DEFAULT_TEST_PHRASE) }
    var status by remember { mutableStateOf("") }

    // Build a streamer fresh per click so it always sees the latest mode +
    // URL + voice config rather than a cached snapshot from first compose.
    // Provider construction lives on WristotleApplication so this composable
    // never reaches into LocalTtsProvider / HttpTtsClient constructors —
    // those are app-wiring concerns, not UI concerns.
    fun freshStreamer(primaryOnly: Boolean = true): TtsStreamer {
        val provider = if (primaryOnly) app.buildPrimaryTtsProvider() else app.buildTtsProvider()
        return TtsStreamer(app.transport, provider)
    }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ─── Master toggle + per-intent picker ──────────────────────
            Text("Speak on watch", style = MaterialTheme.typography.titleMedium)
            Text(
                "Read replies aloud through the watch speaker. Hardware-gated: only Pebble Time 2 and Pebble Round 2 have a speaker — on every other model the chunks are dropped silently. Off until you turn it on.",
                style = MaterialTheme.typography.bodySmall,
            )
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    OutlinedButton(
                        onClick = { settings.setIntentsEnabled(ALL_INTENT_IDS) },
                    ) { Text("Select all") }
                    OutlinedButton(
                        onClick = { settings.setIntentsEnabled(emptySet()) },
                    ) { Text("Deselect all") }
                }
                INTENT_GROUPS.forEachIndexed { idx, group ->
                    if (idx > 0) Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        group.entries.forEach { (id, label) ->
                            FilterChip(
                                selected = id in intents,
                                onClick = { settings.setIntentEnabled(id, id !in intents) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ─── Mode ───────────────────────────────────────────────────
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose which TTS engine produces the audio. You can test the configuration here without enabling the watch dispatch above.",
                style = MaterialTheme.typography.bodySmall,
            )
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
                Spacer(modifier = Modifier.height(4.dp))
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

            // ─── Test surface ───────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Text("Test", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = testPhrase,
                onValueChange = { testPhrase = it },
                label = { Text("Phrase to speak") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            val primaryLabel = when (mode) {
                TtsProviderMode.LOCAL_ONLY, TtsProviderMode.LOCAL_PRIMARY -> "Android TTS"
                TtsProviderMode.CLOUD_PRIMARY -> "HTTP endpoint"
            }
            OutlinedButton(
                onClick = {
                    status = "testing $primaryLabel…"
                    scope.launch {
                        // Pre-warm the watch's TTS state machine BEFORE the
                        // synth round-trip — same shape as the real dispatch
                        // hook in PebbleListenerService.dispatchAndReport.
                        // Without this, the test's first audio chunk lands
                        // while the watch is still mid-handshake; you hear
                        // garbled audio even though real intent dispatch is
                        // clean. Empty body, start=true, end=false: watch
                        // enters BUFFERING; prv_handle_start is idempotent
                        // against the real first chunk's tts_start that
                        // follows.
                        app.transport.sendTtsChunk(ByteArray(0), start = true, end = false)
                        try {
                            val reason = runCatching { freshStreamer().speak(testPhrase) }
                                .getOrElse {
                                    status = "$primaryLabel error: ${it.message ?: it.javaClass.simpleName}"
                                    return@launch
                                }
                            status = if (reason == null) {
                                "$primaryLabel: ok — check watch"
                            } else {
                                "$primaryLabel failed: $reason"
                            }
                        } finally {
                            // Always close the stream so the watch doesn't
                            // stay in BUFFERING (and tts_is_active() doesn't
                            // stay true blocking quick-launch auto-exit)
                            // when speak() throws / fails / the coroutine is
                            // cancelled. Mirrors the dispatch hook's
                            // NonCancellable terminal tts_end — see
                            // PebbleListenerService.dispatchAndReport.
                            withContext(NonCancellable) {
                                runCatching {
                                    app.transport.sendTtsChunk(ByteArray(0), start = false, end = true)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speak on watch (primary only)") }
            OutlinedButton(
                onClick = {
                    status = "streaming 440 Hz to watch…"
                    scope.launch {
                        // Pre-warm + NonCancellable terminal tts_end same as
                        // the speak-on-watch button — if BLE drops mid-tone
                        // or the user navigates away, the watch state
                        // machine still closes cleanly instead of stranding
                        // tts_is_active() true.
                        app.transport.sendTtsChunk(ByteArray(0), start = true, end = false)
                        try {
                            val ok = runCatching { freshStreamer().playTestTone(seconds = 3.0) }
                                .getOrElse {
                                    status = "tone error: ${it.message ?: it.javaClass.simpleName}"
                                    return@launch
                                }
                            status = if (ok) "tone sent to watch" else "tone failed"
                        } finally {
                            withContext(NonCancellable) {
                                runCatching {
                                    app.transport.sendTtsChunk(ByteArray(0), start = false, end = true)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play 440 Hz tone on watch") }
            OutlinedButton(
                onClick = {
                    status = "synthesizing → phone…"
                    scope.launch {
                        val ok = runCatching { freshStreamer().speakOnPhone(testPhrase) }
                            .getOrElse {
                                status = "phone error: ${it.message ?: it.javaClass.simpleName}"
                                return@launch
                            }
                        status = if (ok) "phone playing — clean here = watch path issue if watch crackles"
                                  else "phone playback failed"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speak on phone (same PCM as watch)") }
            OutlinedButton(
                onClick = {
                    status = "tone → phone"
                    freshStreamer().playTonePhone(seconds = 3.0)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play 440 Hz tone on phone") }

            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }

    }   // outer Column wrap
}
