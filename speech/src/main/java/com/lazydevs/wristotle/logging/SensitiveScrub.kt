// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.logging

/**
 * Defence-in-depth scrubber for any text that might surface in a log
 * line, crash file, or bug-report bundle. Strips API-key-shaped tokens
 * from common header / query-string / standalone-prefix shapes used
 * by the providers Wristotle talks to (OpenAI, Anthropic, OpenRouter,
 * Groq, Cloudflare Workers AI, OpenWeather, etc.).
 *
 * Wristotle's own code is audited not to log keys (standing rule), so
 * this catches the cases we don't directly control:
 *
 * - third-party `Throwable.message` strings that happen to embed the
 *   request URL with `?key=…`
 * - stack-frame argument captures from inlined `Bearer …` headers
 * - copy-paste from external docs / curl examples that a future
 *   maintainer might `Log.d()` without thinking
 *
 * Cheap — regex application is O(n × patternCount) and the call sites
 * are off-hot-path (log append, crash write). When in doubt, redact.
 */
object SensitiveScrub {

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // HTTP request / response header forms. Capture the prefix
        // (and `Bearer ` if present) so the line stays readable. Value
        // stops at `&` too so a `?api_key=…&lat=10` query string keeps
        // its trailing params readable when the header-style regex
        // races with the query-string one below.
        Regex("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^&\\s]+") to "$1$2<redacted>",
        Regex("(?i)(x-api-key\\s*[:=]\\s*)[^&\\s]+") to "$1<redacted>",
        Regex("(?i)(anthropic-api-key\\s*[:=]\\s*)[^&\\s]+") to "$1<redacted>",
        Regex("(?i)(api[-_]?key\\s*[:=]\\s*)[^&\\s]+") to "$1<redacted>",
        // Query-string params common across the providers we use.
        // `appid` is OpenWeather's flavour of `api_key`.
        Regex(
            "(?i)([?&](?:api[_-]?key|key|token|access_token|password|secret|appid)=)[^&\\s]+",
        ) to "$1<redacted>",
        // Standalone OpenAI / Anthropic / OpenRouter prefixes. `sk-`
        // followed by 20+ key chars is the canonical shape — `sk-ant-`,
        // `sk-or-`, `sk-proj-` all match the umbrella.
        Regex("\\bsk-(?:ant-|or-|proj-)?[A-Za-z0-9_\\-]{20,}") to "<redacted>",
        // URL-embedded credentials: https://user:pass@host
        Regex("(https?://)([^:/?#\\s]+):([^@/?#\\s]+)@") to "$1<redacted>:<redacted>@",
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var s = input
        for ((pattern, replacement) in PATTERNS) {
            s = s.replace(pattern, replacement)
        }
        return s
    }
}
