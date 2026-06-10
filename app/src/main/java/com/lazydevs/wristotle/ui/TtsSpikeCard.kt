// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.WristotleApplication
import com.lazydevs.wristotle.tts.TtsStreamer
import kotlinx.coroutines.launch

/**
 * Spike card (2026-06-09) — dev-only surface that synthesizes a phrase with
 * Android's TextToSpeech, downsamples to 8 kHz/8-bit, and ships chunks to
 * the watch via [TtsStreamer]. Watch logs throughput on stream-end; pull
 * those logs to evaluate Path B.
 *
 * Lives under Settings → 🔧 Diagnostics for now. Remove from the
 * Diagnostics screen once the spike concludes.
 */
@Composable
fun TtsSpikeCard() {
    val context = LocalContext.current
    val app = context.applicationContext as WristotleApplication
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("Hello, this is Wristotle speaking from your watch.") }
    var status by remember { mutableStateOf("idle") }

    val streamer = remember { TtsStreamer(context, app.transport) }

    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("TTS spike", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Path B bandwidth test — watch plays the phrase through its speaker if BLE can keep up. emery-only.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Phrase to speak") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    status = "synthesizing…"
                    scope.launch {
                        val ok = runCatching { streamer.speak(text) }.getOrElse {
                            status = "error: ${it.message ?: it.javaClass.simpleName}"
                            return@launch
                        }
                        status = if (ok) "sent — check watch + logcat" else "failed"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speak on watch") }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    status = "streaming 440 Hz to watch…"
                    scope.launch {
                        val ok = runCatching { streamer.playTestTone(seconds = 3.0) }.getOrElse {
                            status = "error: ${it.message ?: it.javaClass.simpleName}"
                            return@launch
                        }
                        status = if (ok) "tone sent to watch" else "failed"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play 440 Hz test tone on watch") }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Phone playback — A/B against watch", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    status = "synthesizing → phone…"
                    scope.launch {
                        val ok = runCatching { streamer.speakOnPhone(text) }.getOrElse {
                            status = "error: ${it.message ?: it.javaClass.simpleName}"
                            return@launch
                        }
                        status = if (ok) "phone playing — clean here = watch path bad" else "failed"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speak on phone (same PCM as watch)") }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    status = "tone → phone"
                    streamer.playTonePhone(seconds = 3.0)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play 440 Hz tone on phone") }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
    LaunchedEffect(Unit) { /* no-op — kept in case we want to auto-warm TTS */ }
}
