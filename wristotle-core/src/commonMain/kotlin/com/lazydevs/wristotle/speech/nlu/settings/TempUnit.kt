// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

/**
 * Temperature units the weather feature renders in. The locale-derived
 * default lives in :app (java.util.Locale is JVM-only); commonMain
 * consumers receive it as a lambda from the production wiring.
 *
 * R3 batch 6 — lifted from :app/handlers/WeatherProvider.kt so
 * WeatherSettings can live in commonMain without dragging the rest of
 * the provider with it.
 */
enum class TempUnit { CELSIUS, FAHRENHEIT }
