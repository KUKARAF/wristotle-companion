package com.lazydevs.wristotle.agent

import org.json.JSONObject

/**
 * Best-effort extraction of an error message from a provider error
 * body. Tries `.error.message` (Anthropic + OpenAI), then a flat
 * `.error` string (some OpenAI-compat servers). Returns null on any
 * parse failure so callers can fall back to a generic message.
 */
internal fun String?.providerErrorMessage(): String? {
    if (this == null) return null
    return runCatching {
        val obj = JSONObject(this)
        obj.optJSONObject("error")?.optString("message")?.ifBlank { null }
            ?: obj.optString("error").ifBlank { null }
    }.getOrNull()
}
