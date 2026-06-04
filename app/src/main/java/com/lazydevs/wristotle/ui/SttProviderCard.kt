package com.lazydevs.wristotle.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.speech.audio.AudioSource
import com.lazydevs.wristotle.speech.recognizer.HttpRecognizer
import com.lazydevs.wristotle.speech.recognizer.TranscriptionEvent
import com.lazydevs.wristotle.stt.SttProviderMode
import com.lazydevs.wristotle.stt.SttProviderSettings
import com.lazydevs.wristotle.ui.components.PasswordField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * Settings → 🧠 Models card that chooses how dictation is transcribed.
 *
 *  - **Local only** — on-device Whisper (default; matches every release
 *    before v0.20.0).
 *  - **Local primary** — Whisper first, HTTP fallback if Whisper errors
 *    (model not loaded, inference failure).
 *  - **Cloud primary** — HTTP first, Whisper fallback if the network
 *    call fails (timeout / non-2xx / no model active).
 *
 * The HTTP config (base URL, API key, model) is one block that covers
 * every OpenAI-compatible `/audio/transcriptions` provider — see
 * `speech/.../HttpRecognizer.kt` for the wire shape. The "Test
 * connection" button posts 200 ms of silence to verify reachability +
 * auth shape before the user makes their first real dictation; a
 * configuration error surfaces here instead of as a dead voice query.
 */
@Composable
fun SttProviderCard(settings: SttProviderSettings) {
    val mode by settings.mode.collectAsState()
    val baseUrl by settings.httpBaseUrl.collectAsState()
    val apiKey by settings.httpApiKey.collectAsState()
    val model by settings.httpModel.collectAsState()

    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.settings_stt_provider_header),
                description = stringResource(R.string.settings_stt_provider_desc),
            )

            // ── Mode ────────────────────────────────────────────────────
            Text(
                stringResource(R.string.settings_stt_provider_mode_label),
                style = MaterialTheme.typography.titleSmall,
            )
            RadioRow(
                label = stringResource(R.string.settings_stt_provider_mode_local_only),
                selected = mode == SttProviderMode.LOCAL_ONLY,
                onSelect = { settings.setMode(SttProviderMode.LOCAL_ONLY) },
            )
            RadioRow(
                label = stringResource(R.string.settings_stt_provider_mode_local_primary),
                selected = mode == SttProviderMode.LOCAL_PRIMARY,
                onSelect = { settings.setMode(SttProviderMode.LOCAL_PRIMARY) },
            )
            RadioRow(
                label = stringResource(R.string.settings_stt_provider_mode_cloud_primary),
                selected = mode == SttProviderMode.CLOUD_PRIMARY,
                onSelect = { settings.setMode(SttProviderMode.CLOUD_PRIMARY) },
            )

            // ── HTTP config — only when the mode actually uses it ───────
            // LOCAL_ONLY hides the whole block so a user who doesn't want
            // anything to do with cloud STT doesn't have to look at the
            // fields. Edits persist regardless — so toggling from
            // LOCAL_PRIMARY → LOCAL_ONLY → LOCAL_PRIMARY doesn't lose
            // the previously-typed key.
            AnimatedVisibility(visible = mode != SttProviderMode.LOCAL_ONLY) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_stt_provider_http_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = settings::setHttpBaseUrl,
                        label = { Text(stringResource(R.string.settings_stt_provider_base_url_label)) },
                        placeholder = { Text(stringResource(R.string.settings_stt_provider_base_url_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.settings_stt_provider_base_url_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_stt_provider_compatible_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PasswordField(
                        value = apiKey,
                        onChange = settings::setHttpApiKey,
                        label = stringResource(R.string.settings_stt_provider_api_key_label),
                    )
                    Text(
                        stringResource(R.string.settings_stt_provider_api_key_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = settings::setHttpModel,
                        label = { Text(stringResource(R.string.settings_stt_provider_model_label)) },
                        placeholder = { Text(stringResource(R.string.settings_stt_provider_model_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // ── Test connection ────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                testResult = null
                                testing = true
                                scope.launch {
                                    testResult = testConnection(baseUrl, apiKey, model)
                                    testing = false
                                }
                            },
                            enabled = !testing && baseUrl.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.settings_stt_provider_test_button))
                        }
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    testResult?.let { result ->
                        Text(
                            result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.ok)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** Outcome of the "Test connection" button — only used inside the card,
 *  so it's local. Boolean + message keeps the rendering branch trivial. */
private data class TestResult(val ok: Boolean, val message: String)

/**
 * Posts 200 ms of silence through the recognizer's public flow API and
 * reports the outcome. 200 ms is small enough that providers with
 * per-second billing don't notice it but large enough that endpoints
 * don't reject the request for being trivially short. We don't assert
 * on the returned transcript text — silence legitimately transcribes
 * to "" or a hallucinated subtitle, and that's still proof the endpoint
 * is reachable + the key is accepted.
 *
 * Routed via `HttpRecognizer.transcribe()` (not the `internal`
 * `postTranscription`) so the test path exercises exactly the surface
 * a real dictation does — including the WAV-encoding, multipart, and
 * response parsing.
 */
private suspend fun testConnection(baseUrl: String, apiKey: String, model: String): TestResult {
    val recognizer = HttpRecognizer(baseUrl = baseUrl, apiKey = apiKey, model = model)
    val events = recognizer.transcribe(SilenceAudioSource).toList()
    val terminal = events.lastOrNull()
    return when (terminal) {
        is TranscriptionEvent.Final ->
            TestResult(ok = true, message = "OK — endpoint accepted the request.")
        is TranscriptionEvent.Error -> {
            val msg = terminal.message ?: "request failed"
            // Empty-transcript on silence is success for our purposes —
            // the endpoint accepted the request shape + auth, the
            // provider just returned no text. Don't surface this as a
            // failure to the user.
            if (msg.contains("empty transcript", ignoreCase = true)) {
                TestResult(ok = true, message = "OK — endpoint reachable (no transcript on silence).")
            } else {
                TestResult(ok = false, message = msg)
            }
        }
        else -> TestResult(ok = false, message = "no terminal event from recognizer")
    }
}

/** 200 ms of zero-PCM @ 16 kHz mono delivered as a single chunk —
 *  enough to satisfy the recognizer's "did I see any samples?" check
 *  without crossing any per-second billing threshold. */
private object SilenceAudioSource : AudioSource {
    override val sampleRate = 16_000
    override val channelCount = 1
    override fun samples(): Flow<ShortArray> = flow { emit(ShortArray(3_200)) }
    override fun stop() = Unit
}
