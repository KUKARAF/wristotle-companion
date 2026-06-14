// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Lazy Devs
package com.lazydevs.wristotle.sport

import com.lazydevs.sportskapi.HttpReply
import com.lazydevs.sportskapi.SportHttp
import com.lazydevs.wristotle.speech.util.SimpleHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [SportHttp] for `sportskapi`, wrapping the shared [SimpleHttp]
 * (HttpURLConnection). 8 s timeouts to stay under the watch's 15 s response
 * watchdog; runs on [Dispatchers.IO] since SimpleHttp blocks.
 */
class SportHttpImpl : SportHttp {
    override suspend fun get(url: String, headers: Map<String, String>): HttpReply? =
        withContext(Dispatchers.IO) {
            val (status, body) = SimpleHttp.request(
                url = url,
                headers = headers,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000,
            ) ?: return@withContext null
            HttpReply(status, body)
        }
}
