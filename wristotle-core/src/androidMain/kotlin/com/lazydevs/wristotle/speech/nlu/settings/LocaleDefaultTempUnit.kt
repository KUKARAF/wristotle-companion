// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.settings

import java.util.Locale

/** Locale-derived default unit. US / Liberia / Myanmar still report in °F;
 *  the rest of the world uses °C. Passed to [WeatherSettings] as
 *  `localeDefaultProvider` so commonMain stays Locale-free. */
fun localeDefaultTempUnit(): TempUnit {
    val country = Locale.getDefault().country.uppercase()
    return if (country in FAHRENHEIT_COUNTRIES) TempUnit.FAHRENHEIT else TempUnit.CELSIUS
}

private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM")
