package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lazydevs.wristotle.R
import com.lazydevs.wristotle.handlers.TempUnit
import com.lazydevs.wristotle.settings.WeatherProviderId
import com.lazydevs.wristotle.settings.WeatherSettings
import com.lazydevs.wristotle.ui.components.PasswordField

/**
 * Settings card for the Weather feature — three controls:
 *
 *  - **Unit** — Celsius or Fahrenheit. Default picked from the system locale.
 *  - **Provider** — open-meteo (no key, default) or OpenWeather (free tier
 *    with a user-supplied key).
 *  - **API key** — only visible when the provider is OpenWeather. Masked
 *    text field; persisted on each character via [WeatherSettings.setApiKey].
 *
 * Reads StateFlows directly off [WeatherSettings] — no dedicated VM since the
 * card is just an observer with three setters (mirrors the ReminderSettings
 * card's "no VM, direct app-singleton" pattern).
 */
@Composable
fun WeatherSettingsCard(settings: WeatherSettings) {
    val unit by settings.unit.collectAsState()
    val provider by settings.provider.collectAsState()
    val apiKey by settings.apiKey.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardTitleWithInfo(
                title = stringResource(R.string.weather_settings_header),
                description = stringResource(R.string.weather_settings_desc),
            )

            // ── Unit ────────────────────────────────────────────────────────
            Text(
                stringResource(R.string.weather_settings_unit_label),
                style = MaterialTheme.typography.titleSmall,
            )
            RadioRow(
                label = stringResource(R.string.weather_settings_unit_celsius),
                selected = unit == TempUnit.CELSIUS,
                onSelect = { settings.setUnit(TempUnit.CELSIUS) },
            )
            RadioRow(
                label = stringResource(R.string.weather_settings_unit_fahrenheit),
                selected = unit == TempUnit.FAHRENHEIT,
                onSelect = { settings.setUnit(TempUnit.FAHRENHEIT) },
            )

            Spacer(Modifier.height(8.dp))

            // ── Provider ───────────────────────────────────────────────────
            Text(
                stringResource(R.string.weather_settings_provider_label),
                style = MaterialTheme.typography.titleSmall,
            )
            RadioRow(
                label = stringResource(R.string.weather_settings_provider_openmeteo),
                selected = provider == WeatherProviderId.OPEN_METEO,
                onSelect = { settings.setProvider(WeatherProviderId.OPEN_METEO) },
            )
            RadioRow(
                label = stringResource(R.string.weather_settings_provider_openweather),
                selected = provider == WeatherProviderId.OPEN_WEATHER,
                onSelect = { settings.setProvider(WeatherProviderId.OPEN_WEATHER) },
            )

            // ── OpenWeather API key (conditional) ──────────────────────────
            if (provider == WeatherProviderId.OPEN_WEATHER) {
                Spacer(Modifier.height(4.dp))
                PasswordField(
                    value = apiKey,
                    onChange = settings::setApiKey,
                    label = stringResource(R.string.weather_settings_api_key_label),
                )
                Text(
                    stringResource(R.string.weather_settings_api_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

