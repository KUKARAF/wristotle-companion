// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.backup

import com.lazydevs.wristotle.speech.nlu.backup.*

import com.lazydevs.wristotle.mcp.McpServerEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerJsonTest {

    @Test fun roundTrip_withAuthHeader() {
        val src = McpServerEntity(
            id = 42L,
            name = "github",
            url = "https://api.githubcopilot.com/mcp/",
            streamable = true,
            authHeader = "Bearer ghp_abc",
            enabled = true,
        )
        val encoded = McpServerJson.encode(src, includeAuthHeader = true)
        val decoded = McpServerJson.decode(encoded, McpServerJson.CURRENT_SCHEMA)

        // id always rewrites to 0 (auto-assigned on insert) — see codec comment.
        assertEquals(0L, decoded.id)
        assertEquals(src.name, decoded.name)
        assertEquals(src.url, decoded.url)
        assertEquals(src.streamable, decoded.streamable)
        assertEquals(src.authHeader, decoded.authHeader)
        assertEquals(src.enabled, decoded.enabled)
    }

    @Test fun encode_withoutAuthHeader_stripsField() {
        val src = McpServerEntity(
            id = 1L,
            name = "github",
            url = "https://x/mcp",
            streamable = false,
            authHeader = "Bearer secret",
            enabled = true,
        )
        val encoded = McpServerJson.encode(src, includeAuthHeader = false)
        // Field must be absent from the JSON itself, not just null — so
        // a snooping eye on a plaintext ZIP can't see the secret.
        assertFalse("auth_header must be absent", encoded.has("auth_header"))
        val decoded = McpServerJson.decode(encoded, McpServerJson.CURRENT_SCHEMA)
        assertNull(decoded.authHeader)
    }

    @Test fun encode_includeAuthHeaderTrue_butSourceNull_omitsField() {
        // includeAuthHeader is opt-in for the secret, not a requirement
        // to emit a null when there's no value.
        val src = McpServerEntity(
            id = 1L, name = "x", url = "u", streamable = true,
            authHeader = null, enabled = true,
        )
        val encoded = McpServerJson.encode(src, includeAuthHeader = true)
        assertFalse(encoded.has("auth_header"))
    }

    @Test fun decode_unsupportedSchema_throws() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            McpServerJson.decode(JSONObject(), schema = 999)
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("999"))
    }

    @Test fun decode_disabledServer_preservesEnabledFalse() {
        val src = McpServerEntity(
            id = 1L, name = "x", url = "u", streamable = true,
            authHeader = null, enabled = false,
        )
        val encoded = McpServerJson.encode(src, includeAuthHeader = false)
        val decoded = McpServerJson.decode(encoded, McpServerJson.CURRENT_SCHEMA)
        assertFalse(decoded.enabled)
    }
}