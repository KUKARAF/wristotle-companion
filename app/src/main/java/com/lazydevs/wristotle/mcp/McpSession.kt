// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.mcp

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Aggregates multiple [McpIntegration]s into a single front for the
 * rest of the app. Routes [callTool] back to the right integration by
 * matching `integrationName`.
 *
 * Phase A's only consumer is the Settings UI (list every server's
 * tools, invoke one for testing). Phase B's agent loop will use the
 * same aggregate to give the LLM one unified tool list across N
 * servers.
 *
 * Lifecycle: [openSession] opens every integration in parallel;
 * [closeSession] tears them down. Failures during open/close are
 * logged per-integration so one bad server doesn't take down the
 * others.
 */
class McpSession(val integrations: List<McpIntegration>) {

    private val openMutex = Mutex()
    @Volatile private var isOpen = false

    suspend fun openSession() {
        openMutex.withLock {
            if (isOpen) return
            integrations.forEach { integration ->
                try {
                    integration.connect()
                } catch (t: Throwable) {
                    Log.w(TAG, "connect failed for ${integration.name}", t)
                }
            }
            isOpen = true
        }
    }

    suspend fun closeSession() {
        openMutex.withLock {
            if (!isOpen) return
            integrations.forEach { integration ->
                try {
                    integration.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "close failed for ${integration.name}", t)
                }
            }
            isOpen = false
        }
    }

    /**
     * Tool list across every integration. Each tool carries its
     * `integrationName` so callers don't lose routing info. Per-server
     * failures are swallowed and logged — a single offline server
     * shouldn't make the whole list disappear.
     */
    suspend fun listTools(): List<McpTool> = integrations.flatMap { integration ->
        try {
            integration.listTools()
        } catch (t: Throwable) {
            Log.w(TAG, "listTools failed for ${integration.name}", t)
            emptyList()
        }
    }

    suspend fun resetCache() {
        integrations.forEach { integration ->
            try {
                integration.resetCache()
            } catch (t: Throwable) {
                Log.w(TAG, "resetCache failed for ${integration.name}", t)
            }
        }
    }

    suspend fun callTool(
        integrationName: String,
        toolName: String,
        json: Map<String, JsonElement>,
    ): ToolCallResult {
        val target = integrations.firstOrNull { it.name == integrationName }
            ?: return ToolCallResult.Failure("no integration named '$integrationName'")
        return target.callTool(toolName, json)
    }

    companion object {
        private const val TAG = "McpSession"
    }
}