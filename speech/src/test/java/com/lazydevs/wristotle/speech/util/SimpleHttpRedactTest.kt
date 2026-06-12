// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The OpenWeather provider puts the API key in the query string (`&appid=…`),
 * and SimpleHttp logs the URL on a transport error. These guard that the key
 * never survives into the logged URL (standing rule: no API key in any log).
 */
class SimpleHttpRedactTest {

    @Test fun `appid value is redacted`() {
        val url = "https://api.openweathermap.org/data/2.5/weather?lat=37&lon=-122&appid=SECRET123"
        val out = SimpleHttp.redactSecrets(url)
        assertFalse("key must not survive: $out", out.contains("SECRET123"))
        assertEquals(
            "https://api.openweathermap.org/data/2.5/weather?lat=37&lon=-122&appid=<redacted>",
            out,
        )
    }

    @Test fun `various secret param names are redacted`() {
        for (param in listOf("api_key", "apikey", "api-key", "token", "access_token", "key", "secret")) {
            val out = SimpleHttp.redactSecrets("https://x.test/v1?$param=TOPSECRET&q=tokyo")
            assertFalse("$param leaked: $out", out.contains("TOPSECRET"))
        }
    }

    @Test fun `non-secret params and key-less urls are untouched`() {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=37&longitude=-122&hourly=temperature"
        assertEquals(url, SimpleHttp.redactSecrets(url))
    }

    @Test fun `redaction stops at the next param boundary`() {
        // The value after appid must be cut at '&', not swallow the rest.
        val out = SimpleHttp.redactSecrets("https://x.test/v1?appid=SECRET&units=metric")
        assertEquals("https://x.test/v1?appid=<redacted>&units=metric", out)
    }
}
