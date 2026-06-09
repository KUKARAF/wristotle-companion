// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort extraction of a provider's error message from a response
 * body. Tries `.error.message` (Anthropic + OpenAI shape), then a flat
 * `.error` string (some OpenAI-compat servers). Returns null on any
 * parse failure so callers can fall back to a generic message.
 *
 * The two shapes are dispatched on the actual type of `.error` —
 * otherwise an `error` object with a blank `message` would fall through
 * to the flat-string branch and surface e.g. `{"message":""}` as the
 * "error message" the user sees.
 *
 * **Sibling impl note:** `:speech/util/HttpUtil.kt` carries an
 * equivalent `providerErrorMessage` written against `org.json` (used
 * by `HttpRecognizer` in the JVM-only `:speech` module). The two MUST
 * stay semantically aligned — change one, change the other. The
 * duplication is structural: `:wristotle-core/androidMain` already
 * depends on `:speech` (via `ModelFileStorage`), so `:speech` can't
 * depend back on `:wristotle-core` without breaking that edge first.
 */
fun String?.providerErrorMessage(): String? {
    if (this == null) return null
    return runCatching {
        val errorField = Json.parseToJsonElement(this).jsonObject["error"]
        when (errorField) {
            is JsonObject -> errorField["message"]
                ?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            is JsonPrimitive -> errorField.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }.getOrNull()
}
