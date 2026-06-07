// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Tool exposed by an MCP server, as surfaced to the rest of the app.
 *
 * Trimmed from the SDK's `Tool` type — we keep the fields an agent or
 * settings UI actually needs (name, description, JSON-Schema for the
 * input). `integrationName` is added so an aggregated tool list across
 * multiple integrations can be routed back to the right session.
 *
 * The full SDK `Tool` carries annotations, output schemas, and per-tool
 * metadata that phase A doesn't use; those can be added later without a
 * wire-format change since this is a pure in-memory type.
 */
data class McpTool(
    val integrationName: String,
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?,
)