// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.mcp

/**
 * Outcome of one MCP tool call. Text-flattened — `TextContent` parts
 * are joined by newlines on success, error text or thrown message on
 * failure. The SDK's structured `CallToolResult` is collapsed at the
 * integration boundary so callers don't have to depend on SDK types.
 */
sealed interface ToolCallResult {
    data class Success(val text: String) : ToolCallResult
    data class Failure(val message: String) : ToolCallResult
}