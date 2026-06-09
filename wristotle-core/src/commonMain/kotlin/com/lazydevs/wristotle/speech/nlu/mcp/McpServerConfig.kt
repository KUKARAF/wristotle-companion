// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.speech.nlu.mcp

/**
 * Portable shape of an enabled MCP server, sufficient for AgentLoop
 * to construct an [McpIntegration] without touching the Android-only
 * `McpServerEntity` (Room). The :app side translates its persisted
 * Room rows into this shape before handing them to the loop.
 *
 * R5 batch 3.
 */
data class McpServerConfig(
    val name: String,
    val url: String,
    val streamable: Boolean,
    val authHeader: String?,
)
